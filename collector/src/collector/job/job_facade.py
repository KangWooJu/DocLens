"""job 접수, 조회, 취소, 재개, claim, heartbeat, 종결의 TX 범위"""

from uuid import UUID

from psycopg_pool import ConnectionPool

from collector.contract.collection_request import CollectionRequest
from collector.job.job_model import ClaimedJob, JobOutcome
from collector.job.job_repository import Lease
from collector.job.job_service import Accept, JobService, JobStatus


class JobFacade:
    """접수, 조회, 취소, 재개, claim, heartbeat, 종결 기록의 TX 범위"""

    def __init__(
        self,
        pool: ConnectionPool,
        service: JobService,
    ) -> None:
        self._pool = pool
        self._service = service

    def submit(self, request: CollectionRequest) -> Accept:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.accept(conn, request)

    def status(self, job_id: UUID) -> JobStatus:
        with self._pool.connection() as conn:
            return self._service.status(conn, job_id)

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
