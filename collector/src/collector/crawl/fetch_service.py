"""문서 송신, 응답 분류, 원본 저장, 문서 결과와 닫힘 검증 기록"""

from dataclasses import dataclass, replace
from uuid import UUID

from collector.config import CrawlPolicy
from collector.crawl.crawl_repository import CrawlRepository, DocumentResult
from collector.crawl.page_markers import PageMarkers
from collector.crawl.send_policy import (
    Accepted,
    Rejected,
    Response,
    Retry,
    TransportFailed,
    classify,
    content_type,
    retry,
)
from collector.crawl.transport_gate import (
    AttemptsExhausted,
    Deferred,
    Fetch,
    NotDue,
    RobotsDenied,
    TransportGate,
    UrlDenied,
)
from collector.crawl.urls import canonical_url
from collector.db import Conn
from collector.job.job_model import ClaimedJob, Waiting
from collector.job.job_repository import JobRepository, Lease
from collector.store.object_store import ObjectRef, ObjectStore, raw_key


@dataclass(frozen=True)
class DueTarget:
    run_id: UUID
    document_id: str
    url: str
    source_id: str


@dataclass(frozen=True)
class Stored:
    """Accepted 응답을 put_once한 뒤"""

    raw: ObjectRef
    response: Response
    markers: PageMarkers


@dataclass(frozen=True)
class Skipped:
    """아직 기한 전, 아무것도 쓰지 않음"""


type FetchResult = Stored | Retry | Rejected | Deferred | Skipped


class FetchService:
    def __init__(
        self,
        gate: TransportGate,
        store: ObjectStore,
        repository: CrawlRepository,
        jobs: JobRepository,
        policy: CrawlPolicy,
    ) -> None:
        self._gate = gate
        self._store = store
        self._repository = repository
        self._jobs = jobs
        self._policy = policy

    def recover(
        self,
        conn: Conn,
        lease: Lease,
    ) -> None:
        """이전 epoch가 남긴 SENDING 송신과 문서 정리"""

        run = self._repository.run(conn, lease.job_id)
        self._jobs.recover(
            conn,
            lease,
            None if run is None else run.run_id,
            self._policy.recover_delay,
        )

    def next_due(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> DueTarget | Waiting | None:
        """None이면 모든 문서 종결, Waiting은 가장 이른 다음 시도까지"""

        row = self._repository.due_target(conn, run_id)

        if row is not None:
            return DueTarget(run_id, *row)

        open_count, wait_seconds = self._repository.open_targets(conn, run_id)

        if open_count == 0:
            return None

        if wait_seconds is None:
            raise RuntimeError(f"run {run_id} has a document still sending in this epoch")

        return Waiting("RETRY", max(wait_seconds, 0.0))

    def fetch(
        self,
        job: ClaimedJob,
        target: DueTarget,
    ) -> FetchResult:
        """TX 밖: gate 송신, 분류, Accepted면 원본 저장, 시도 상한 판정은 record가 행의 시도 수로"""

        source = job.source(target.source_id)
        fetch = Fetch(
            url=target.url,
            purpose="DOCUMENT",
            source_id=source.source_id,
            scope=source.scope,
            run_id=target.run_id,
            document_id=target.document_id,
        )

        try:
            response = self._gate.send(job.lease, fetch)
        except NotDue:
            return Skipped()
        except AttemptsExhausted:
            return Rejected(
                "HTTP_RETRY_EXHAUSTED",
                True,
                None,
            )
        except RobotsDenied:
            return Rejected(
                "ROBOTS_DENIED",
                True,
                None,
            )
        except UrlDenied:
            return Rejected(
                "URL_DENIED",
                True,
                None,
            )
        except TransportFailed as failed:
            return classify(
                failed,
                1,
                self._policy,
                source.source_type,
            )

        if isinstance(response, Deferred):
            return response

        decision = classify(
            response,
            1,
            self._policy,
            source.source_type,
        )

        if not isinstance(decision, Accepted):
            return replace(decision, observed_at=response.observed_at)

        raw = self._store.put_once(
            raw_key(
                source.project,
                source.exact_version,
                response.body,
                source.source_type,
            ),
            response.body,
        )
        return Stored(
            raw=raw,
            response=response,
            markers=decision.markers,
        )

    def record(
        self,
        conn: Conn,
        job: ClaimedJob,
        target: DueTarget,
        result: FetchResult,
    ) -> None:
        epoch = job.lease.epoch

        match result:
            case Stored(raw=raw, response=response, markers=markers):
                headers = response.headers
                self._repository.succeed_target(
                    conn,
                    target.run_id,
                    target.document_id,
                    epoch,
                    DocumentResult(
                        raw=raw,
                        status=response.status,
                        final_url=response.url,
                        content_type=content_type(headers),
                        last_modified=headers.get("last-modified") or None,
                        etag=headers.get("etag") or None,
                        version=markers.version,
                        versioned_url=markers.versioned_url,
                        source_tag=markers.source_tag,
                        observed_at=response.observed_at,
                    ),
                )
                self._check_closure(
                    conn,
                    job,
                    target,
                    markers,
                )
            case Retry(http_status=status, observed_at=observed_at):
                attempt = self._repository.attempt(
                    conn,
                    target.run_id,
                    target.document_id,
                )
                decision = retry(
                    attempt,
                    status,
                    self._policy,
                )

                if isinstance(decision, Retry):
                    self._repository.retry_target(
                        conn,
                        target.run_id,
                        target.document_id,
                        epoch,
                        decision.delay_seconds,
                    )
                else:
                    self._repository.fail_target(
                        conn,
                        target.run_id,
                        target.document_id,
                        epoch,
                        decision.error_code,
                        status,
                        observed_at,
                    )
            case Rejected(error_code=code, http_status=status, observed_at=observed_at):
                self._repository.fail_target(
                    conn,
                    target.run_id,
                    target.document_id,
                    epoch,
                    code,
                    status,
                    observed_at,
                )
            case _:
                pass

    def _check_closure(
        self,
        conn: Conn,
        job: ClaimedJob,
        target: DueTarget,
        markers: PageMarkers,
    ) -> None:
        """페이지 nav가 봉인 목록 밖 링크를 가지거나 버전 표지가 다르면 drift"""

        source = job.source(target.source_id)
        links = {
            canonical_url(
                target.url,
                href,
                source.scope,
            )
            for href in markers.nav_hrefs
        }
        known = sorted(link for link in links if link is not None)

        if markers.version != source.exact_version or self._repository.unknown_links(
            conn,
            target.run_id,
            known,
        ):
            self._repository.mark_drift(conn, target.run_id)
