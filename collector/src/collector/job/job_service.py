"""접수 검증, registry 대조, 상태 조회와 전이, claim과 종결"""

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID, uuid4

from collector.config import CrawlPolicy
from collector.contract.canonical_json import JsonValue, canonical_bytes, content_hash, utc_text
from collector.contract.collection_request import CollectionRequest
from collector.db import Conn
from collector.errors import RequestRejected
from collector.job.job_model import Cancelled, ClaimedJob, Failed, JobOutcome, Review, Waiting
from collector.job.job_repository import JobRepository, Lease, StatusRow
from collector.registry.registry_repository import RegistryRepository
from collector.registry.source_registry import match_inputs, registry_hash
from collector.store.object_store import ObjectRef


@dataclass(frozen=True)
class Accept:
    request_id: str
    job_id: UUID
    request_hash: str
    state: str
    duplicate: bool
    accepted_at: datetime

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "schemaVersion": 1,
            "requestId": self.request_id,
            "jobId": str(self.job_id),
            "requestHash": self.request_hash,
            "state": self.state,
            "duplicate": self.duplicate,
            "acceptedAt": utc_text(self.accepted_at),
        }


@dataclass(frozen=True)
class JobStatus:
    job_id: UUID
    request_id: UUID
    state: str
    wait_reason: str | None
    attempt_count: int
    lease_epoch: int
    next_attempt_at: datetime | None
    updated_at: datetime
    result: ObjectRef | None
    last_error_code: str | None
    cancel_requested: bool

    @classmethod
    def of(cls, row: StatusRow) -> "JobStatus":
        (
            job_id,
            request_id,
            state,
            wait_reason,
            attempt_count,
            lease_epoch,
            next_attempt_at,
            updated_at,
            result_key,
            result_hash,
            result_size,
            last_error_code,
            cancel_requested,
        ) = row
        result = None

        if result_key is not None and result_hash is not None and result_size is not None:
            result = ObjectRef(
                object_key=result_key,
                content_hash=result_hash,
                size_bytes=result_size,
            )

        return cls(
            job_id=job_id,
            request_id=request_id,
            state=state,
            wait_reason=wait_reason,
            attempt_count=attempt_count,
            lease_epoch=lease_epoch,
            next_attempt_at=next_attempt_at,
            updated_at=updated_at,
            result=result,
            last_error_code=last_error_code,
            cancel_requested=cancel_requested,
        )

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "schemaVersion": 1,
            "requestId": str(self.request_id),
            "jobId": str(self.job_id),
            "state": self.state,
            "waitReason": self.wait_reason,
            "attemptCount": self.attempt_count,
            "leaseEpoch": self.lease_epoch,
            "nextAttemptAt": None if self.next_attempt_at is None else utc_text(self.next_attempt_at),
            "updatedAt": utc_text(self.updated_at),
            "result": None if self.result is None else self.result.to_json(),
            "lastErrorCode": self.last_error_code,
        }


class JobService:
    def __init__(
        self,
        repository: JobRepository,
        registry: RegistryRepository,
        policy: CrawlPolicy,
    ) -> None:
        self._repository = repository
        self._registry = registry
        self._policy = policy

    def accept(
        self,
        conn: Conn,
        request: CollectionRequest,
    ) -> Accept:
        """registry 대조 뒤 requestId 멱등 삽입, 같은 id 다른 본문은 ID_INPUT_CONFLICT"""

        request_id = UUID(request.request_id)
        trace_id = UUID(request.trace_id)

        if request.request_kind != "COLLECT":
            raise RequestRejected(
                "VALIDATION_FAILED",
                "only COLLECT requests are accepted",
                request_id=request_id,
                trace_id=trace_id,
            )

        approved = self._registry.approved(conn)

        if request.source_registry_hash != registry_hash(approved):
            raise RequestRejected(
                "SOURCE_UNAPPROVED",
                "sourceRegistryHash differs from the current approved registry",
                request_id=request_id,
                trace_id=trace_id,
            )

        code = match_inputs(request.inputs, approved)

        if code is not None:
            raise RequestRejected(
                code,
                "inputs must be unique approved sources with at least one required input",
                request_id=request_id,
                trace_id=trace_id,
            )

        body = request.to_json()
        request_hash = content_hash(canonical_bytes(body))
        inserted = self._repository.insert(
            conn,
            job_id=uuid4(),
            request_id=request_id,
            request_hash=request_hash,
            body=body,
        )
        job_id, stored_hash, state, created_at = self._repository.by_request(conn, request_id)

        if stored_hash != request_hash:
            raise RequestRejected(
                "ID_INPUT_CONFLICT",
                "requestId was accepted with a different body",
                request_id=request_id,
                trace_id=trace_id,
            )

        return Accept(
            request_id=request.request_id,
            job_id=job_id,
            request_hash=request_hash,
            state=state,
            duplicate=not inserted,
            accepted_at=created_at,
        )

    def status(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> JobStatus:
        row = self._repository.status(conn, job_id)

        if row is None:
            raise RequestRejected("NOT_FOUND", f"unknown job {job_id}")

        return JobStatus.of(row)

    def cancel(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> JobStatus:
        """대기 중이면 즉시 CANCELLED, 실행 중이면 다음 fence에서"""

        row = self._repository.cancel(conn, job_id)

        if row is None:
            current = self.status(conn, job_id)
            raise RequestRejected("INVALID_TRANSITION", f"job {job_id} is already {current.state}")

        return JobStatus.of(row)

    def resume(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> JobStatus:
        """NEEDS_REVIEW, FAILED_FINAL만 QUEUED로, 시도와 송신 기록은 그대로"""

        row = self._repository.resume(conn, job_id)

        if row is not None:
            return JobStatus.of(row)

        current = self.status(conn, job_id)

        if current.state not in ("NEEDS_REVIEW", "FAILED_FINAL") or current.cancel_requested:
            raise RequestRejected("INVALID_TRANSITION", f"job {job_id} is {current.state}, nothing to resume")

        if current.last_error_code == "SCOPE_MISMATCH":
            raise RequestRejected("SCOPE_MISMATCH", "the run scope drifted, submit a new request")

        raise RequestRejected("SOURCE_UNAPPROVED", "a source of this job is no longer approved")

    def claim(
        self,
        conn: Conn,
        owner_id: UUID,
    ) -> ClaimedJob | None:
        row = self._repository.claim(
            conn,
            owner_id,
            self._policy.lease,
        )

        if row is None:
            return None

        job_id, epoch, body = row
        request = CollectionRequest.from_json(body)
        rows = self._registry.sources(
            conn,
            [item.source_id for item in request.inputs],
        )
        return ClaimedJob(
            lease=Lease(
                job_id=job_id,
                owner_id=owner_id,
                epoch=epoch,
            ),
            request=request,
            sources=tuple(row.source for row in rows),
        )

    def next_due(self, conn: Conn) -> tuple[int, float | None]:
        return self._repository.next_due(conn)

    def heartbeat(
        self,
        conn: Conn,
        lease: Lease,
    ) -> bool:
        return self._repository.heartbeat(
            conn,
            lease,
            self._policy.lease,
        )

    def finish(
        self,
        conn: Conn,
        lease: Lease,
        outcome: JobOutcome,
    ) -> JobStatus | None:
        """None이면 lease 상실, Succeeded는 readyRun 기록 TX가 종결하므로 받지 않음"""

        match outcome:
            case Waiting(reason=reason, delay_seconds=delay_seconds):
                row = self._repository.finish_waiting(
                    conn,
                    lease,
                    reason,
                    delay_seconds,
                )
            case Review(code=code):
                row = self._repository.finish_stopped(
                    conn,
                    lease,
                    "NEEDS_REVIEW",
                    code,
                )
            case Failed(code=code):
                row = self._repository.finish_stopped(
                    conn,
                    lease,
                    "FAILED_FINAL",
                    code,
                )
            case Cancelled():
                row = self._repository.finish_cancelled(conn, lease)
            case _:
                raise ValueError(f"finish does not record {outcome}")

        return None if row is None else JobStatus.of(row)
