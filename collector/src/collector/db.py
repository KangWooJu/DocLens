"""PG 연결 pool과 worker 전역 lock"""

from collections.abc import Callable, Iterator
from contextlib import contextmanager

import psycopg
from psycopg import errors
from psycopg.rows import TupleRow
from psycopg_pool import ConnectionPool

from collector.config import CrawlPolicy, Settings

Conn = psycopg.Connection[TupleRow]

# DB 연결 끊김, PG 재시작, 서버 측 TX 시간 초과
DB_ERRORS = (
    psycopg.OperationalError,
    errors.IdleInTransactionSessionTimeout,
    errors.TransactionTimeout,
)

WORKER_LOCK = "SELECT pg_try_advisory_lock(hashtext('collector-worker'))"


def open_pool(
    settings: Settings,
    policy: CrawlPolicy,
) -> ConnectionPool:
    """worker 주 루프와 heartbeat 스레드용 연결 2개, 꺼낼 때마다 끊긴 연결 교체"""

    return ConnectionPool(
        settings.db_url,
        min_size=2,
        max_size=2,
        kwargs={
            "autocommit": True,
            "connect_timeout": int(policy.db_connect_timeout),
            "options": policy.db_options(),
        },
        timeout=policy.db_connect_timeout,
        check=ConnectionPool.check_connection,
        open=True,
    )


@contextmanager
def worker_lock(
    settings: Settings,
    policy: CrawlPolicy,
) -> Iterator[Callable[[], bool] | None]:
    """pool 밖 전용 연결의 advisory lock, 잡으면 연결 생존 확인 함수, 못 잡으면 None

    autocommit이고 pool의 timeout 설정을 붙이지 않아 idle 상태로 lock을 오래 유지
    """

    with psycopg.connect(
        settings.db_url,
        autocommit=True,
        connect_timeout=int(policy.db_connect_timeout),
        keepalives=1,
        keepalives_idle=10,
        keepalives_interval=5,
        keepalives_count=3,
    ) as conn:
        row = (
            conn
            .execute(WORKER_LOCK)
            .fetchone()
        )

        if row is None or not row[0]:
            yield None
            return

        def alive() -> bool:
            try:
                conn.execute("SELECT 1")
            except psycopg.OperationalError:
                return False

            return True

        yield alive
