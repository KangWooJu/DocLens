"""job 접수, 조회, readyRun 조회, 취소, 재개, claim, heartbeat, 종결의 TX 범위"""

from uuid import UUID

from psycopg_pool import ConnectionPool

from collector.contract.canonical_json import content_hash
from collector.contract.collection_request import CollectionRequest
from collector.errors import RequestRejected
from collector.job.job_model import ClaimedJob, JobOutcome
from collector.job.job_repository import Lease
from collector.job.job_service import Accept, JobService, JobStatus
from collector.store.object_store import ObjectStore


class JobFacade:
    """접수, 조회, 취소, 재개, claim, heartbeat, 종결 기록의 TX 범위"""

    def __init__(
        self,
        pool: ConnectionPool,
        service: JobService,
        store: ObjectStore,
    ) -> None:
        self._pool = pool
        self._service = service
        self._store = store

    def submit(self, request: CollectionRequest) -> Accept:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.accept(conn, request)

    def status(self, job_id: UUID) -> JobStatus:
        with self._pool.connection() as conn:
            return self._service.status(conn, job_id)

    def ready_run(self, job_id: UUID) -> bytes:
        """SUCCEEDED job의 readyRun 저장 bytes, 기록한 hash와 크기를 대조"""

        status = self.status(job_id)
        result = status.result

        if status.state != "SUCCEEDED" or result is None:
            raise RequestRejected(
                "INPUT_NOT_SEALED",
                f"job {job_id} is {status.state}",
                request_id=status.request_id,
            )

        body = self._store.get(result.object_key)

        if body is None:
            raise RequestRejected(
                "STORAGE_FAILED",
                f"readyRun object of job {job_id} is missing",
                request_id=status.request_id,
            )

        if content_hash(body) != result.content_hash or len(body) != result.size_bytes:
            raise RequestRejected(
                "HASH_MISMATCH",
                f"readyRun object of job {job_id} differs from the recorded hash",
                request_id=status.request_id,
            )

        return body

    def cancel(self, job_id: UUID) -> JobStatus:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.cancel(conn, job_id)

    def resume(self, job_id: UUID) -> JobStatus:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.resume(conn, job_id)

    def claim(self, owner_id: UUID) -> ClaimedJob | None:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.claim(conn, owner_id)

    def next_due(self) -> tuple[int, float | None]:
        """claim할 job이 없을 때 대기 job 수와 다음 기한까지 남은 초"""

        with self._pool.connection() as conn:
            return self._service.next_due(conn)

    def heartbeat(self, lease: Lease) -> bool:
        """autocommit 한 문장, False면 lease 상실"""

        with self._pool.connection() as conn:
            return self._service.heartbeat(conn, lease)

    def finish(
        self,
        lease: Lease,
        outcome: JobOutcome,
    ) -> JobStatus | None:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.finish(
                conn,
                lease,
                outcome,
            )
