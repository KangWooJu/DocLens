"""모든 상태 전이 SQL을 psycopg 실제 바인딩(서버 측 $n)으로 한 번씩, NULL 가지마다 실행"""

import threading
import time
from collections.abc import Callable, Iterator
from types import ModuleType
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg.errors import DeadlockDetected, UniqueViolation
from psycopg.types.json import Jsonb

from collector import db
from collector.crawl import transport_gate as gate
from collector.job import job_repository as jobs
from collector.registry import registry_repository as sources
from collector.registry.source_registry import definition_hash
from tests.conftest import definition, insert_run
from tests.support import pending_sql as pending

HOST = "docs.spring.io"
SOURCE_ID = "spring-boot-4.1.3-reference"
SQL_MODULES = (db, jobs, sources, gate, pending)


def statements(module: ModuleType) -> dict[str, str]:
    """모듈의 대문자 SQL 상수"""

    return {
        name: value
        for name, value in vars(module).items()
        if name.isupper()
        and isinstance(value, str)
        and value.split()[0] in ("SELECT", "INSERT", "UPDATE", "DELETE", "WITH")
    }


class Recorder:
    """실행한 SQL 문자열을 기록하는 실행기"""

    def __init__(self) -> None:
        self.executed: set[str] = set()

    def __call__(
        self,
        conn: psycopg.Connection,
        sql: str,
        params: dict[str, object] | None = None,
    ) -> psycopg.Cursor:
        self.executed.add(sql)
        return conn.execute(sql, params)


@pytest.fixture
def conn(pg: psycopg.Connection) -> Iterator[psycopg.Connection]:
    yield pg


def lease(
    job_id: UUID,
    owner_id: UUID,
    epoch: int,
) -> dict[str, object]:
    return {"job_id": job_id, "owner_id": owner_id, "epoch": epoch}


def reserve(
    run: Recorder,
    conn: psycopg.Connection,
    held: dict[str, object] | None,
    document: tuple[UUID, str] | None,
    purpose: str,
) -> int:
    """예약 TX 1~6단계, held가 None이면 probe"""

    with conn.transaction():
        if held is not None:
            assert run(
                conn,
                jobs.FENCE,
                held,
            ).fetchone() == (False,)

        approval = run(
            conn,
            gate.RESERVE_APPROVAL,
            {"source_id": SOURCE_ID, "is_probe": held is None},
        )
        assert approval.fetchone() is not None
        run(
            conn,
            gate.RESERVE_HOST_INSERT,
            {"host": HOST},
        )
        host = run(
            conn,
            gate.RESERVE_HOST_LOCK,
            {"host": HOST},
        ).fetchone()
        assert host is not None and host[7] <= 0
        run(
            conn,
            gate.RESERVE_EXPIRE,
            {"in_flight_expiry_seconds": 150.0},
        )

        if document is not None:
            run_id, document_id = document
            attempt = run(
                conn,
                gate.RESERVE_ATTEMPT,
                {"run_id": run_id, "document_id": document_id, "epoch": held["epoch"]},
            ).fetchone()
            assert attempt is not None

        (send_id,) = run(
            conn,
            gate.RESERVE_SEND,
            {
                "budget_limit": 3000,
                "host": HOST,
                "job_id": None if held is None else held["job_id"],
                "epoch": None if held is None else held["epoch"],
                "probe_source": SOURCE_ID if held is None else None,
                "purpose": purpose,
                "document_id": None if document is None else document[1],
                "path": "/robots.txt" if document is None else "/spring-boot/4.1/reference/p.html",
            },
        ).fetchone()

    return send_id


def finish_send(
    run: Recorder,
    conn: psycopg.Connection,
    send_id: int,
    status: int | None,
    kind: str | None,
    robots: tuple[int, str] | None = None,
    open_seconds: float | None = None,
) -> None:
    """결과 TX: host 행, 송신 행, host 상태"""

    with conn.transaction():
        run(
            conn,
            gate.RESULT_HOST_LOCK,
            {"host": HOST},
        )
        assert run(
            conn,
            gate.RESULT_SENT,
            {"send_id": send_id, "status": status, "kind": kind},
        ).rowcount == 1
        run(
            conn,
            gate.RESULT_HOST_UPDATE,
            {
                "host": HOST,
                "wait_seconds": 0.0,
                "failures": 0 if kind is None else 1,
                "level": 0,
                "open_seconds": open_seconds,
                "half_open": False,
                "throttle": 0,
                "robots_status": None if robots is None else robots[0],
                "robots_body": None if robots is None else robots[1],
            },
        )


def claim(
    run: Recorder,
    conn: psycopg.Connection,
    owner_id: UUID,
) -> tuple[UUID, int, object] | None:
    with conn.transaction():
        run(conn, jobs.CANCEL_EXPIRED)
        return run(
            conn,
            jobs.CLAIM,
            {"owner_id": owner_id, "lease_seconds": 30.0},
        ).fetchone()


def new_job(
    run: Recorder,
    conn: psycopg.Connection,
) -> UUID:
    job_id = uuid4()
    inserted = run(
        conn,
        jobs.INSERT_JOB,
        {
            "job_id": job_id,
            "request_id": uuid4(),
            "request_hash": "sha256:" + "b" * 64,
            "body": Jsonb({"inputs": [{"sourceId": SOURCE_ID}]}),
        },
    ).fetchone()
    assert inserted == (job_id,)
    return job_id


def expire_lease(
    conn: psycopg.Connection,
    job_id: UUID,
) -> None:
    conn.execute(
        "UPDATE collector.jobs SET lease_until = clock_timestamp() - interval '1 second' WHERE job_id = %s",
        (job_id,),
    )


def test_every_statement_runs_with_real_binding(conn: psycopg.Connection) -> None:
    run = Recorder()
    entry = definition()
    old_hash = definition_hash(entry)

    # registry: import, 재정의, probe 송신과 기록, 승인
    run(
        conn,
        sources.INSERT_SOURCE,
        {"source_id": SOURCE_ID, "definition": Jsonb(entry), "definition_hash": old_hash},
    )

    with conn.transaction():
        assert run(
            conn,
            sources.SELECT_SOURCE_FOR_UPDATE,
            {"source_id": SOURCE_ID},
        ).fetchone()[2] == "BLOCKED"

    entry = {**entry, "officialLinkEvidence": "https://spring.io/projects/spring-boot#learn"}
    new_hash = definition_hash(entry)
    redefined = run(
        conn,
        sources.REDEFINE_SOURCE,
        {"source_id": SOURCE_ID, "definition": Jsonb(entry), "definition_hash": new_hash},
    )
    assert redefined.rowcount == 1

    robots = reserve(
        run,
        conn,
        None,
        None,
        "ROBOTS",
    )
    finish_send(
        run,
        conn,
        robots,
        200,
        None,
        robots=(200, "User-agent: *\nAllow: /\n"),
    )
    probe = {"state": "PASSED", "version": "4.1.3", "raw_hash": "sha256:" + "a" * 64, "source_id": SOURCE_ID}
    assert run(
        conn,
        sources.RECORD_PROBE,
        {**probe, "probed_hash": old_hash},
    ).fetchone() is None
    assert run(
        conn,
        sources.RECORD_PROBE,
        {**probe, "probed_hash": new_hash},
    ).fetchone() == (SOURCE_ID,)
    approved = run(
        conn,
        sources.APPROVE,
        {"source_id": SOURCE_ID, "by": "tester", "profile": "spring-antora-reference@2"},
    )
    assert approved.fetchone() == (new_hash,)
    assert len(run(conn, sources.SELECT_APPROVED).fetchall()) == 1
    assert len(run(
        conn,
        sources.SELECT_SOURCES,
        {"source_ids": [SOURCE_ID, "missing"]},
    ).fetchall()) == 1
    assert run(
        conn,
        gate.RESERVE_APPROVAL,
        {"source_id": SOURCE_ID, "is_probe": True},
    ).fetchone() is None
    assert (
        conn
        .execute("SELECT robots_status FROM collector.host_states")
        .fetchone()
    ) == (200,)

    # 접수, 다음 기한, claim, heartbeat
    job_id = new_job(run, conn)
    (request_id,) = (
        conn
        .execute("SELECT request_id FROM collector.jobs WHERE job_id = %s", (job_id,))
        .fetchone()
    )
    assert run(conn, jobs.INSERT_JOB, {
        "job_id": uuid4(),
        "request_id": request_id,
        "request_hash": "sha256:" + "b" * 64,
        "body": Jsonb({}),
    }).fetchone() is None
    assert run(
        conn,
        jobs.SELECT_JOB_BY_REQUEST,
        {"request_id": request_id},
    ).fetchone()[0] == job_id
    assert run(
        conn,
        jobs.SELECT_STATUS,
        {"job_id": job_id},
    ).fetchone()[2] == "QUEUED"
    pending_jobs, due = run(conn, pending.NEXT_DUE).fetchone()
    assert pending_jobs == 1 and due <= 0

    first_owner = uuid4()
    assert claim(
        run,
        conn,
        first_owner,
    )[:2] == (job_id, 1)
    held = lease(
        job_id,
        first_owner,
        1,
    )
    assert run(
        conn,
        jobs.HEARTBEAT,
        {**held, "lease_seconds": 30.0},
    ).fetchone() is not None
    run_id, batch_id, (first, second) = insert_run(conn, job_id)

    # 문서 1: 성공, NULL 가지(kind, robots, open_seconds)
    sent = reserve(
        run,
        conn,
        held,
        (run_id, first),
        "DOCUMENT",
    )
    finish_send(
        run,
        conn,
        sent,
        200,
        None,
    )

    with conn.transaction():
        run(
            conn,
            jobs.FENCE,
            held,
        )
        succeeded = run(
            conn,
            pending.TARGET_SUCCEEDED,
            {
                "run_id": run_id,
                "document_id": first,
                "epoch": 1,
                "raw_key": "raw/spring-boot/4.1.3/" + "1" * 64 + ".html",
                "raw_hash": "sha256:" + "1" * 64,
                "raw_size": 10,
                "status": 200,
                "final_url": "https://docs.spring.io/spring-boot/4.1/reference/p000.html",
                "content_type": "text/html",
                "last_modified": None,
                "etag": None,
                "version": "4.1.3",
                "versioned_url": None,
                "source_tag": "v4.1.3",
                "observed_at": "2026-11-24T00:00:00Z",
            },
        )
        assert succeeded.rowcount == 1

    # 문서 2: SENDING을 남긴 채 lease 만료, epoch 2 인수와 recover
    reserve(
        run,
        conn,
        held,
        (run_id, second),
        "DOCUMENT",
    )
    expire_lease(conn, job_id)
    second_owner = uuid4()
    assert claim(
        run,
        conn,
        second_owner,
    )[:2] == (job_id, 2)
    held = lease(
        job_id,
        second_owner,
        2,
    )
    recover = {"job_id": job_id, "epoch": 2, "run_id": run_id, "recover_delay_seconds": 0.0}

    with conn.transaction():
        run(
            conn,
            jobs.FENCE,
            held,
        )
        run(
            conn,
            jobs.RECOVER_LOCK_HOSTS,
            recover,
        )
        assert run(
            conn,
            jobs.RECOVER_SENDS,
            recover,
        ).rowcount == 1
        assert run(
            conn,
            jobs.RECOVER_TARGETS,
            recover,
        ).rowcount == 1

    # 시간 초과 결과(상태 코드 NULL, 서킷 시각 값), 재시도, 중단 송신, 확정 실패(observed_at NULL)
    timed_out = reserve(
        run,
        conn,
        held,
        (run_id, second),
        "DOCUMENT",
    )
    finish_send(
        run,
        conn,
        timed_out,
        None,
        "TIMEOUT",
        open_seconds=0.0,
    )
    retry = {"run_id": run_id, "document_id": second, "epoch": 2, "delay_seconds": 0.0}
    assert run(
        conn,
        pending.TARGET_RETRY,
        retry,
    ).rowcount == 1
    interrupted = reserve(
        run,
        conn,
        held,
        (run_id, second),
        "DOCUMENT",
    )
    assert run(
        conn,
        gate.RESULT_UNKNOWN,
        {"send_id": interrupted},
    ).rowcount == 1
    failed = run(
        conn,
        pending.TARGET_FAILED,
        {
            "run_id": run_id,
            "document_id": second,
            "epoch": 2,
            "error_code": "HTTP_RETRY_EXHAUSTED",
            "needs_review": True,
            "status": None,
            "observed_at": None,
        },
    )
    assert failed.rowcount == 1
    assert (
        conn
        .execute(
            "SELECT attempt_count, state FROM collector.collection_targets WHERE document_id = %s",
            (second,),
        )
        .fetchone()
    ) == (3, "FAILED")
    assert run(
        conn,
        gate.RESERVE_ATTEMPT_EXHAUSTED,
        {"run_id": run_id, "document_id": second},
    ).fetchone() == (True,)

    # 봉인: 승인 재확인, batch 값 고정, readyRun 고정, readyRun 기록과 SUCCEEDED
    inputs = [{"sourceId": SOURCE_ID}]

    with conn.transaction():
        run(
            conn,
            jobs.FENCE,
            held,
        )
        assert run(
            conn,
            pending.SEAL_APPROVAL_RECHECK,
            {"request_inputs": Jsonb(inputs)},
        ).fetchone() == (0,)
        assert run(
            conn,
            pending.SEAL_BATCH,
            {"batch_id": batch_id, "epoch": 2},
        ).rowcount == 1

    conn.execute(
        """
        UPDATE collector.collection_batches
        SET targets_key = 'manifests/r/b/targets.jsonl', targets_hash = %(hash)s, targets_size = 1,
            outcomes_key = 'manifests/r/b/outcomes.jsonl', outcomes_hash = %(hash)s, outcomes_size = 1,
            envelope_key = 'manifests/r/b/envelope.json', envelope_hash = %(hash)s, envelope_size = 1
        WHERE batch_id = %(batch_id)s
        """,
        {"batch_id": batch_id, "hash": "sha256:" + "e" * 64},
    )
    conn.execute(
        """
        UPDATE collector.collection_runs
        SET discovery_state = 'SEALED', sealed_at = clock_timestamp(),
            targets_key = 'discovery/r/targets.jsonl', targets_hash = %(hash)s, targets_size = 1,
            discovery_key = 'discovery/r/discovery.json', discovery_hash = %(hash)s, discovery_size = 1
        WHERE run_id = %(run_id)s
        """,
        {"run_id": run_id, "hash": "sha256:" + "e" * 64},
    )
    ready = {"key": "manifests/r/ready-run.json", "hash": "sha256:" + "f" * 64, "size": 99}

    with conn.transaction():
        run(
            conn,
            jobs.FENCE,
            held,
        )
        assert run(
            conn,
            pending.READY_RUN_FIX,
            {"run_id": run_id, "new_uuid": uuid4()},
        ).rowcount == 1

    with conn.transaction():
        run(
            conn,
            jobs.FENCE,
            held,
        )
        assert run(
            conn,
            pending.READY_RUN_RECORD,
            {**ready, "run_id": run_id},
        ).rowcount == 1
        assert run(
            conn,
            jobs.SUCCEED,
            {**held, **ready},
        ).fetchone() == (job_id,)

    # drift가 있는 run은 readyRun 고정 0 row
    drift_job = new_job(run, conn)
    drift_run, _, _ = insert_run(
        conn,
        drift_job,
        documents=1,
    )
    assert run(
        conn,
        pending.RUN_DRIFT,
        {"run_id": drift_run},
    ).rowcount == 1
    assert conn.execute(pending.READY_RUN_FIX, {"run_id": drift_run, "new_uuid": uuid4()}).rowcount == 0

    # 종결 4종: 다음 KST 00:00 대기, 숫자 대기, 검토, 실패 뒤 재개와 취소
    owner = uuid4()
    assert claim(
        run,
        conn,
        owner,
    )[:2] == (drift_job, 1)
    waiting = run(
        conn,
        jobs.FINISH_WAITING,
        {**lease(
            drift_job,
            owner,
            1,
        ), "reason": "BUDGET", "delay": None},
    )
    assert waiting.fetchone()[2] == "RETRY_WAIT"
    assert (
        conn
        .execute(
            """
            SELECT next_attempt_at = (date_trunc('day', clock_timestamp() AT TIME ZONE 'Asia/Seoul') + interval '1 day')
                                     AT TIME ZONE 'Asia/Seoul'
            FROM collector.jobs WHERE job_id = %s
            """,
            (drift_job,),
        )
        .fetchone()
    ) == (True,)
    assert claim(
        run,
        conn,
        owner,
    ) is None
    conn.execute(
        "UPDATE collector.jobs SET next_attempt_at = clock_timestamp() WHERE job_id = %s",
        (drift_job,),
    )
    assert claim(
        run,
        conn,
        owner,
    )[:2] == (drift_job, 2)
    held = lease(
        drift_job,
        owner,
        2,
    )
    assert run(
        conn,
        jobs.FINISH_WAITING,
        {**held, "reason": "RETRY", "delay": 0.0},
    ).fetchone()[2] == "RETRY_WAIT"
    assert claim(
        run,
        conn,
        owner,
    )[:2] == (drift_job, 3)
    held = lease(
        drift_job,
        owner,
        3,
    )
    stopped = run(
        conn,
        jobs.FINISH_STOPPED,
        {**held, "state": "NEEDS_REVIEW", "code": "SCOPE_MISMATCH"},
    )
    assert stopped.fetchone()[2] == "NEEDS_REVIEW"
    assert run(
        conn,
        jobs.RESUME,
        {"job_id": drift_job},
    ).fetchone() is None

    failed_job = new_job(run, conn)
    assert claim(
        run,
        conn,
        owner,
    )[:2] == (failed_job, 1)
    held = lease(
        failed_job,
        owner,
        1,
    )
    assert run(
        conn,
        jobs.FINISH_STOPPED,
        {**held, "state": "FAILED_FINAL", "code": "INTERNAL_ERROR"},
    ).fetchone()
    assert run(
        conn,
        jobs.RESUME,
        {"job_id": failed_job},
    ).fetchone()[2] == "QUEUED"
    assert run(
        conn,
        jobs.CANCEL,
        {"job_id": failed_job},
    ).fetchone()[2] == "CANCELLED"

    running_job = new_job(run, conn)
    assert claim(
        run,
        conn,
        owner,
    )[:2] == (running_job, 1)
    held = lease(
        running_job,
        owner,
        1,
    )
    assert (
        conn
        .execute(jobs.CANCEL, {"job_id": running_job})
        .fetchone()
    )[2] == "RUNNING"
    assert (
        conn
        .execute(jobs.FENCE, held)
        .fetchone()
    ) == (True,)
    assert (
        conn
        .execute(jobs.FINISH_WAITING, {**held, "reason": "RETRY", "delay": 0.0})
        .fetchone()
    ) is None
    assert run(
        conn,
        jobs.FINISH_CANCELLED,
        held,
    ).fetchone()[2] == "CANCELLED"

    # 철회: 다음 예약과 봉인 재확인에서 멈춤
    assert run(
        conn,
        sources.REVOKE,
        {"source_id": SOURCE_ID, "by": "tester"},
    ).fetchone() == (new_hash,)
    assert (
        conn
        .execute(gate.RESERVE_APPROVAL, {"source_id": SOURCE_ID, "is_probe": False})
        .fetchone()
    ) is None
    assert (
        conn
        .execute(pending.SEAL_APPROVAL_RECHECK, {"request_inputs": Jsonb(inputs)})
        .fetchone()
    ) == (1,)

    # worker 전역 lock
    assert run(conn, db.WORKER_LOCK).fetchone() == (True,)
    conn.execute("SELECT pg_advisory_unlock_all()")

    # 모든 SQL 상수가 한 번 이상 실행됨
    expected = {sql for module in SQL_MODULES for sql in statements(module).values()}
    missing = [
        f"{module.__name__}.{name}"
        for module in SQL_MODULES
        for name, sql in statements(module).items()
        if sql not in run.executed
    ]
    assert missing == []
    assert len(expected) >= 40


def wait_for_lock(
    conn: psycopg.Connection,
    pid: int,
) -> None:
    deadline = time.monotonic() + 10

    while time.monotonic() < deadline:
        row = (
            conn
            .execute("SELECT wait_event_type FROM pg_stat_activity WHERE pid = %s", (pid,))
            .fetchone()
        )

        if row == ("Lock",):
            return

        time.sleep(0.02)

    raise AssertionError(f"backend {pid} never waited for a lock")


@pytest.fixture
def open_send(conn: psycopg.Connection) -> int:
    """승인된 source, host 행, 진행 중 probe가 아닌 job 송신 하나"""

    entry = definition()
    conn.execute(
        "INSERT INTO collector.sources (source_id, definition, definition_hash) VALUES (%s, %s, %s)",
        (SOURCE_ID, Jsonb(entry), definition_hash(entry)),
    )
    conn.execute(gate.RESERVE_HOST_INSERT, {"host": HOST})
    (send_id,) = (
        conn
        .execute(
            gate.RESERVE_SEND,
            {
                "budget_limit": 3000,
                "host": HOST,
                "job_id": None,
                "epoch": None,
                "probe_source": SOURCE_ID,
                "purpose": "ROBOTS",
                "document_id": None,
                "path": "/robots.txt",
            },
        )
        .fetchone()
    )
    return send_id


def reservation_until_insert(
    conn: psycopg.Connection,
    after_host_lock: Callable[[], None] = lambda: None,
) -> str:
    """probe 예약 TX, 간격이 남았으면 WAIT, 다른 송신이 진행 중이면 IN_FLIGHT"""

    try:
        with conn.transaction():
            assert (
                conn
                .execute(gate.RESERVE_APPROVAL, {"source_id": SOURCE_ID, "is_probe": True})
                .fetchone()
            )
            conn.execute(gate.RESERVE_HOST_INSERT, {"host": HOST})
            host = (
                conn
                .execute(gate.RESERVE_HOST_LOCK, {"host": HOST})
                .fetchone()
            )
            after_host_lock()

            if host[7] is not None and host[7] > 0:
                raise _Rollback

            conn.execute(gate.RESERVE_EXPIRE, {"in_flight_expiry_seconds": 150.0})
            conn.execute(
                gate.RESERVE_SEND,
                {
                    "budget_limit": 3000,
                    "host": HOST,
                    "job_id": None,
                    "epoch": None,
                    "probe_source": SOURCE_ID,
                    "purpose": "ROBOTS",
                    "document_id": None,
                    "path": "/robots.txt",
                },
            )
    except _Rollback:
        return "WAIT"
    except UniqueViolation:
        return "IN_FLIGHT"

    return "RESERVED"


class _Rollback(Exception):
    pass


def in_thread(
    database_url: str,
    work: Callable[[psycopg.Connection], str],
) -> tuple[threading.Thread, dict[str, object]]:
    result: dict[str, object] = {}
    connected = threading.Event()

    def target() -> None:
        with psycopg.connect(database_url, autocommit=True) as conn:
            result["pid"] = conn.info.backend_pid
            connected.set()

            try:
                result["value"] = work(conn)
            except Exception as error:  # 교착 여부를 주 스레드가 판정
                result["error"] = error

    thread = threading.Thread(target=target)
    thread.start()
    assert connected.wait(10)
    return thread, result


def test_result_then_reservation_does_not_deadlock(
    conn: psycopg.Connection,
    open_send: int,
    database_url: str,
) -> None:
    with conn.transaction():
        conn.execute(gate.RESULT_HOST_LOCK, {"host": HOST})
        thread, result = in_thread(database_url, reservation_until_insert)
        wait_for_lock(conn, result["pid"])
        conn.execute(gate.RESULT_SENT, {"send_id": open_send, "status": 200, "kind": None})
        conn.execute(
            gate.RESULT_HOST_UPDATE,
            {
                "host": HOST,
                "wait_seconds": 2.0,
                "failures": 0,
                "level": 0,
                "open_seconds": None,
                "half_open": False,
                "throttle": 0,
                "robots_status": None,
                "robots_body": None,
            },
        )

    thread.join(10)

    assert not isinstance(result.get("error"), DeadlockDetected)
    assert result.get("value") == "WAIT", result


def test_reservation_then_result_does_not_deadlock(
    conn: psycopg.Connection,
    open_send: int,
    database_url: str,
) -> None:
    holding = threading.Event()
    release = threading.Event()

    def pause() -> None:
        holding.set()
        assert release.wait(10)

    def reservation(other: psycopg.Connection) -> str:
        return reservation_until_insert(other, pause)

    thread, result = in_thread(database_url, reservation)
    assert holding.wait(10)

    def result_tx(other: psycopg.Connection) -> str:
        with other.transaction():
            other.execute(gate.RESULT_HOST_LOCK, {"host": HOST})
            other.execute(gate.RESULT_SENT, {"send_id": open_send, "status": 200, "kind": None})

        return "SENT"

    # 예약 쪽이 host 행을 쥔 채 결과 TX를 시작하고, 예약이 계속되면 진행 중 송신에 막힘
    result_thread, sent = in_thread(database_url, result_tx)
    wait_for_lock(conn, sent["pid"])
    release.set()
    thread.join(10)
    result_thread.join(10)

    assert sent.get("value") == "SENT", sent
    assert not isinstance(result.get("error"), DeadlockDetected)
    assert result.get("value") == "IN_FLIGHT", result
