"""Facade의 conn.transaction() 문맥 관리자 의미 확인 (PG 17, psycopg pool)"""

import signal
import time
from collections.abc import Callable, Iterator

import psycopg
import pytest
from psycopg.pq import TransactionStatus
from psycopg_pool import ConnectionPool

from collector.config import CrawlPolicy, Settings
from collector.db import open_pool

INSERT = "INSERT INTO collector.host_states (host) VALUES (%s)"


@pytest.fixture
def pool(settings: Settings) -> Iterator[ConnectionPool]:
    pool = open_pool(settings, CrawlPolicy())

    try:
        yield pool
    finally:
        pool.close()


def hosts(pg: psycopg.Connection) -> list[str]:
    return [row[0] for row in pg.execute("SELECT host FROM collector.host_states ORDER BY host")]


class Boom(Exception):
    pass


def test_nested_transaction_is_a_savepoint(
    pool: ConnectionPool,
    pg: psycopg.Connection,
) -> None:
    with pool.connection() as conn, conn.transaction():
        conn.execute(INSERT, ("outer",))

        with pytest.raises(Boom), conn.transaction():
            conn.execute(INSERT, ("inner",))
            raise Boom

    assert hosts(pg) == ["outer"]


def test_outer_exception_rolls_back_and_returns_idle_connection(
    pool: ConnectionPool,
    pg: psycopg.Connection,
) -> None:
    with pytest.raises(Boom), pool.connection() as conn, conn.transaction():
        conn.execute(INSERT, ("lost",))
        raise Boom

    assert hosts(pg) == []

    with pool.connection() as conn:
        assert conn.info.transaction_status == TransactionStatus.IDLE


@pytest.mark.parametrize(
    ("policy", "error"),
    [
        (CrawlPolicy(idle_in_tx_timeout=1.0), psycopg.errors.IdleInTransactionSessionTimeout),
        (CrawlPolicy(result_margin=1.0, statement_timeout=1.0), psycopg.errors.TransactionTimeout),
    ],
)
def test_policy_timeouts_cut_the_connection_and_pool_replaces_it(
    settings: Settings,
    pg: psycopg.Connection,
    policy: CrawlPolicy,
    error: type[Exception],
) -> None:
    """db_options의 timeout이 실제로 연결을 끊음, 오류는 OperationalError가 아닌 InternalError 계열"""

    pool = open_pool(settings, policy)

    try:
        with pytest.raises(error), pool.connection() as conn, conn.transaction():
            conn.execute(INSERT, ("timed-out",))
            time.sleep(2)
            conn.execute("SELECT 1")

        assert issubclass(error, psycopg.InternalError)
        assert hosts(pg) == []

        for _ in range(3):
            with pool.connection() as conn:
                assert (
                    conn
                    .execute("SELECT 1")
                    .fetchone()
                ) == (1,)
    finally:
        pool.close()


def raise_keyboard_interrupt() -> None:
    raise KeyboardInterrupt


def raise_from_sigterm_handler() -> None:
    def handler(signum: int, frame: object) -> None:
        raise SystemExit(143)

    previous = signal.signal(signal.SIGTERM, handler)

    try:
        signal.raise_signal(signal.SIGTERM)
    finally:
        signal.signal(signal.SIGTERM, previous)


@pytest.mark.parametrize("interrupt", [raise_keyboard_interrupt, raise_from_sigterm_handler])
def test_interrupt_leaves_no_open_transaction(
    pool: ConnectionPool,
    pg: psycopg.Connection,
    interrupt: Callable[[], None],
) -> None:
    with pytest.raises((KeyboardInterrupt, SystemExit)), pool.connection() as conn, conn.transaction():
        conn.execute(INSERT, ("interrupted",))
        interrupt()

    open_transactions = (
        pg
        .execute(
            """
            SELECT count(*)
            FROM pg_stat_activity
            WHERE datname = current_database() AND state LIKE 'idle in transaction%%'
            """,
        )
        .fetchone()
    )

    assert open_transactions == (0,)
    assert hosts(pg) == []


def test_statement_outside_transaction_commits_immediately(
    pool: ConnectionPool,
    pg: psycopg.Connection,
) -> None:
    with pool.connection() as conn:
        conn.execute(INSERT, ("autocommit",))

        assert hosts(pg) == ["autocommit"]
        assert conn.info.transaction_status == TransactionStatus.IDLE
