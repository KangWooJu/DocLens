"""객체 단계: 대상 목록, discovery, batch 봉인, outcomes, envelope, readyRun과 job 종결"""

from dataclasses import dataclass
from uuid import UUID, uuid4

from collector.crawl import manifest_writer as writer
from collector.crawl.crawl_repository import BatchRow, CrawlRepository, ObjectKind, TargetRow
from collector.db import Conn
from collector.job.job_model import ClaimedJob, Review
from collector.job.job_repository import JobRepository
from collector.store.object_store import ObjectRef, ObjectStore


@dataclass(frozen=True)
class PendingObject:
    """커밋된 PG 행만으로 만든 결정적 bytes, schema 검증 통과"""

    kind: ObjectKind
    owner_id: UUID
    key: str
    body: bytes


class SealService:
    def __init__(
        self,
        store: ObjectStore,
        repository: CrawlRepository,
        jobs: JobRepository,
    ) -> None:
        self._store = store
        self._repository = repository
        self._jobs = jobs

    def next_object(
        self,
        conn: Conn,
        job: ClaimedJob,
    ) -> PendingObject | Review | None:
        """입력이 준비된 다음 객체 하나, 봉인 값과 readyRun 값은 이 TX에서 고정, None이면 지금 만들 것 없음"""

        repository = self._repository
        run = repository.run(conn, job.lease.job_id)
        assert run is not None and run.discovery_state != "DISCOVERING"
        context = writer.RunInputs.of(job)
        batches = repository.batches(conn, run.run_id)
        targets = repository.targets(conn, run.run_id)

        for batch in batches:
            if batch.targets is None:
                return PendingObject(
                    kind="BATCH_TARGETS",
                    owner_id=batch.batch_id,
                    key=writer.batch_targets_key(run, batch),
                    body=writer.targets_jsonl(context, _of(targets, batch)),
                )

        if run.targets is None:
            return PendingObject(
                kind="RUN_TARGETS",
                owner_id=run.run_id,
                key=writer.run_targets_key(run),
                body=writer.targets_jsonl(context, targets),
            )

        if run.discovery is None:
            return PendingObject(
                kind="DISCOVERY",
                owner_id=run.run_id,
                key=writer.discovery_key(run),
                body=writer.discovery_json(
                    context,
                    run,
                    batches,
                    repository.projections(conn, run.run_id),
                ),
            )

        for batch in batches:
            if batch.envelope is not None:
                continue

            if batch.outcomes is not None:
                return PendingObject(
                    kind="ENVELOPE",
                    owner_id=batch.batch_id,
                    key=writer.envelope_key(run, batch),
                    body=writer.envelope_json(
                        context,
                        run,
                        batch,
                        _of(targets, batch),
                    ),
                )

            if batch.sealed_at is None:
                if self._unapproved(conn, job):
                    return Review("SOURCE_UNAPPROVED")

                if not repository.seal_batch(
                    conn,
                    batch.batch_id,
                    job.lease.epoch,
                ):
                    continue

            return PendingObject(
                kind="OUTCOMES",
                owner_id=batch.batch_id,
                key=writer.outcomes_key(run, batch),
                body=writer.outcomes_jsonl(
                    context,
                    run,
                    _of(targets, batch),
                ),
            )

        if any(batch.envelope is None for batch in batches):
            return None

        if run.drift_detected:
            return Review("SCOPE_MISMATCH")

        if self._unapproved(conn, job):
            return Review("SOURCE_UNAPPROVED")

        if run.ready_run_id is None:
            fixed = repository.fix_ready_run(
                conn,
                run.run_id,
                uuid4(),
            )
            run = repository.run(conn, job.lease.job_id)
            assert fixed and run is not None

        return PendingObject(
            kind="READY_RUN",
            owner_id=run.run_id,
            key=writer.ready_run_key(run),
            body=writer.ready_run_json(
                run,
                batches,
                targets,
                repository.projections(conn, run.run_id),
            ),
        )

    def put(self, pending: PendingObject) -> ObjectRef:
        """TX 밖: commit된 bytes만 업로드, 같은 key 같은 bytes는 채택"""

        return self._store.put_once(pending.key, pending.body)

    def record(
        self,
        conn: Conn,
        job: ClaimedJob,
        pending: PendingObject,
        ref: ObjectRef,
    ) -> None:
        """READY_RUN이면 같은 TX에서 job SUCCEEDED"""

        self._repository.record_object(
            conn,
            pending.kind,
            pending.owner_id,
            ref,
        )

        if pending.kind == "READY_RUN":
            self._jobs.succeed(
                conn,
                job.lease,
                ref,
            )

    def _unapproved(
        self,
        conn: Conn,
        job: ClaimedJob,
    ) -> bool:
        inputs = [item.to_json() for item in job.request.inputs]
        return self._repository.unapproved(conn, inputs) > 0


def _of(
    targets: tuple[TargetRow, ...],
    batch: BatchRow,
) -> tuple[TargetRow, ...]:
    return tuple(target for target in targets if target.batch_id == batch.batch_id)

