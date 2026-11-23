"""claim, lease, recover, cancel, resume, worker lock"""

import time
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest

from collector.app import App, build_app
from collector.config import CrawlPolicy, Settings
from collector.db import worker_lock
from collector.job.job_model import Failed, Review, Waiting
from collector.job.job_repository import CancelRequested, JobRepository, Lease, LeaseLost
from tests.conftest import approve_sources, definition, insert_run
from tests.support.cli import cli
from tests.support.loopback_connector import LoopbackConnector

SHORT_LEASE = CrawlPolicy(
    lease=2.0,
    heartbeat_interval=0.5,
    total_deadline=1.0,
    result_margin=1.0,
    statement_timeout=1.0,
    in_flight_expiry=20.0,
)


def submit_job(
    app: App,
    settings: Settings,
    directory: Path,
) -> UUID:
    approve_sources(
        app,
        settings,
        directory,
        definition(),
    )
    request = app.registry.draft_request(["spring-boot-4.1.3-reference"])
    return app.jobs.submit(request).job_id


def state(
    pg: psycopg.Connection,
    job_id: UUID,
) -> tuple[str, int, int, bool]:
    return (
        pg
        .execute(
            "SELECT state, attempt_count, lease_epoch, cancel_requested FROM collector.jobs WHERE job_id = %s",
            (job_id,),
        )
        .fetchone()
    )


def test_claim_skips_a_row_locked_by_another_claim(
    app: App,
    settings: Settings,
    tmp_path: Path,
) -> None:
    job_id = submit_job(
        app,
        settings,
        tmp_path,
    )
    repository = JobRepository()

    with (
        psycopg.connect(settings.db_url, autocommit=True) as first,
        psycopg.connect(settings.db_url, autocommit=True) as second,
    ):
        with first.transaction():
            claimed = repository.claim(
                first,
                uuid4(),
                30.0,
            )

            with second.transaction():
                started = time.monotonic()
                assert repository.claim(
                    second,
                    uuid4(),
                    30.0,
                ) is None
                assert time.monotonic() - started < 1

        assert claimed[:2] == (job_id, 1)


def test_claimed_job_carries_request_and_sources(
    app: App,
    settings: Settings,
    tmp_path: Path,
) -> None:
    job_id = submit_job(
        app,
        settings,
        tmp_path,
    )
    owner = uuid4()
    claimed = app.jobs.claim(owner)

    assert claimed.lease == Lease(
        job_id=job_id,
        owner_id=owner,
        epoch=1,
    )
    assert [source.source_id for source in claimed.sources] == ["spring-boot-4.1.3-reference"]
    assert claimed.request.inputs[0].exact_version == "4.1.3"
    assert app.jobs.heartbeat(claimed.lease)
    assert app.jobs.claim(uuid4()) is None


def test_expired_lease_moves_to_next_owner(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    app = build_app(settings, LoopbackConnector(9), policy=SHORT_LEASE)

    try:
        job_id = submit_job(
            app,
            settings,
            tmp_path,
        )
        old = app.jobs.claim(uuid4()).lease
        time.sleep(2.2)
        new = app.jobs.claim(uuid4()).lease

        assert new.epoch == 2
        assert state(pg, job_id)[:3] == ("RUNNING", 2, 2)
        assert not app.jobs.heartbeat(old)
        assert app.jobs.finish(old, Failed("INTERNAL_ERROR")) is None

        with pytest.raises(LeaseLost), pg.transaction():
            JobRepository().fence(pg, old)
    finally:
        app.close()


def test_open_fence_blocks_takeover_until_commit(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    app = build_app(settings, LoopbackConnector(9), policy=SHORT_LEASE)

    try:
        submit_job(
            app,
            settings,
            tmp_path,
        )
        old = app.jobs.claim(uuid4()).lease

        with pg.transaction():
            JobRepository().fence(pg, old)
            time.sleep(2.2)

            assert app.jobs.claim(uuid4()) is None

        assert app.jobs.claim(uuid4()).lease.epoch == 2
    finally:
        app.close()


def test_recover_closes_sends_of_the_previous_epoch(
    app: App,
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    job_id = submit_job(
        app,
        settings,
        tmp_path,
    )
    app.jobs.claim(uuid4())
    run_id, _, (retried, exhausted) = insert_run(pg, job_id)
    pg.execute(
        """
        UPDATE collector.collection_targets
        SET state = 'SENDING', sending_epoch = 1,
            attempt_count = CASE WHEN document_id = %s THEN 1 ELSE 3 END
        WHERE run_id = %s
        """,
        (retried, run_id),
    )
    pg.execute("INSERT INTO collector.host_states (host) VALUES ('docs.spring.io')")
    pg.execute(
        """
        INSERT INTO collector.http_sends (budget_limit, host, job_id, lease_epoch, purpose, document_id, path)
        VALUES (3000, 'docs.spring.io', %s, 1, 'DOCUMENT', %s, '/p.html')
        """,
        (job_id, retried),
    )
    pg.execute(
        "UPDATE collector.jobs SET lease_until = clock_timestamp() - interval '1 second' WHERE job_id = %s",
        (job_id,),
    )
    lease = app.jobs.claim(uuid4()).lease

    with pg.transaction():
        repository = JobRepository()
        repository.fence(pg, lease)
        repository.recover(
            pg,
            lease,
            run_id,
            30.0,
        )

    targets = dict(
        (row[0], row[1:])
        for row in pg.execute(
            """
            SELECT document_id, state, attempt_count, error_code,
                   extract(epoch FROM next_attempt_at - clock_timestamp())
            FROM collector.collection_targets WHERE run_id = %s
            """,
            (run_id,),
        )
    )

    assert targets[retried][:3] == ("PENDING", 1, None)
    assert 28 < targets[retried][3] <= 30
    assert targets[exhausted][:3] == ("FAILED", 3, "HTTP_RETRY_EXHAUSTED")
    assert (
        pg
        .execute("SELECT state FROM collector.http_sends")
        .fetchall()
    ) == [("UNKNOWN",)]


def test_cancel_by_state(
    app: App,
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    queued = submit_job(
        app,
        settings,
        tmp_path,
    )

    assert cli(
        app,
        capsys,
        "cancel",
        queued,
    )[1][0]["state"] == "CANCELLED"
    assert cli(
        app,
        capsys,
        "cancel",
        queued,
    )[0] == 3
    assert cli(
        app,
        capsys,
        "resume",
        queued,
    )[0] == 3
    assert cli(
        app,
        capsys,
        "cancel",
        uuid4(),
    )[0] == 4

    waiting = app.jobs.submit(app.registry.draft_request(["spring-boot-4.1.3-reference"])).job_id
    lease = app.jobs.claim(uuid4()).lease
    assert app.jobs.finish(lease, Waiting("HOST", 600.0)).state == "RETRY_WAIT"

    code, out, _ = cli(
        app,
        capsys,
        "cancel",
        waiting,
    )
    assert (code, out[0]["state"], out[0]["waitReason"]) == (0, "CANCELLED", None)

    running = app.jobs.submit(app.registry.draft_request(["spring-boot-4.1.3-reference"])).job_id
    lease = app.jobs.claim(uuid4()).lease

    assert cli(
        app,
        capsys,
        "cancel",
        running,
    )[1][0]["state"] == "RUNNING"
    assert state(pg, running) == ("RUNNING", 1, 1, True)

    with pytest.raises(CancelRequested), pg.transaction():
        JobRepository().fence(pg, lease)

    assert app.jobs.finish(lease, Review("SOURCE_UNAPPROVED")) is None
    assert app.jobs.heartbeat(lease)


def test_cancel_requested_running_job_is_cancelled_after_lease_expiry(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    app = build_app(settings, LoopbackConnector(9), policy=SHORT_LEASE)

    try:
        job_id = submit_job(
            app,
            settings,
            tmp_path,
        )
        app.jobs.claim(uuid4())
        app.jobs.cancel(job_id)
        time.sleep(2.2)

        assert app.jobs.claim(uuid4()) is None
        assert state(pg, job_id)[0] == "CANCELLED"
    finally:
        app.close()


def test_resume_keeps_attempts_and_sends(
    app: App,
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    job_id = submit_job(
        app,
        settings,
        tmp_path,
    )
    lease = app.jobs.claim(uuid4()).lease
    pg.execute(
        """
        INSERT INTO collector.http_sends (budget_limit, host, job_id, lease_epoch, purpose, path, state,
                                          http_status, finished_at)
        VALUES (3000, 'docs.spring.io', %s, 1, 'ROBOTS', '/robots.txt', 'SENT', 503, clock_timestamp())
        """,
        (job_id,),
    )
    app.jobs.finish(lease, Review("ROBOTS_UNREACHABLE"))
    code, out, _ = cli(
        app,
        capsys,
        "resume",
        job_id,
    )

    assert (code, out[0]["state"], out[0]["attemptCount"], out[0]["lastErrorCode"]) == (0, "QUEUED", 1, None)
    assert (
        pg
        .execute("SELECT count(*) FROM collector.http_sends")
        .fetchone()
    ) == (1,)

    lease = app.jobs.claim(uuid4()).lease
    assert lease.epoch == 2
    app.jobs.finish(lease, Failed("INTERNAL_ERROR"))
    assert cli(
        app,
        capsys,
        "resume",
        job_id,
    )[1][0]["attemptCount"] == 2


def test_resume_refuses_scope_mismatch_and_revoked_sources(
    app: App,
    settings: Settings,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    drifted = submit_job(
        app,
        settings,
        tmp_path,
    )
    app.jobs.finish(app.jobs.claim(uuid4()).lease, Review("SCOPE_MISMATCH"))
    code, _, err = cli(
        app,
        capsys,
        "resume",
        drifted,
    )

    assert (code, err[0]["code"]) == (3, "SCOPE_MISMATCH")

    revoked = app.jobs.submit(app.registry.draft_request(["spring-boot-4.1.3-reference"])).job_id
    app.jobs.finish(app.jobs.claim(uuid4()).lease, Review("ROBOTS_UNREACHABLE"))
    app.registry.revoke("spring-boot-4.1.3-reference", "tester")
    code, _, err = cli(
        app,
        capsys,
        "resume",
        revoked,
    )

    assert (code, err[0]["code"]) == (3, "SOURCE_UNAPPROVED")


def test_finish_waiting_for_budget_waits_until_next_kst_midnight(
    app: App,
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    job_id = submit_job(
        app,
        settings,
        tmp_path,
    )
    status = app.jobs.finish(app.jobs.claim(uuid4()).lease, Waiting("BUDGET", None))

    assert (status.state, status.wait_reason) == ("RETRY_WAIT", "BUDGET")
    assert (
        pg
        .execute(
            """
            SELECT (next_attempt_at AT TIME ZONE 'Asia/Seoul')::time = '00:00'
               AND next_attempt_at > clock_timestamp()
               AND next_attempt_at <= clock_timestamp() + interval '1 day'
            FROM collector.jobs WHERE job_id = %s
            """,
            (job_id,),
        )
        .fetchone()
    ) == (True,)
    assert app.jobs.claim(uuid4()) is None


def test_worker_lock_allows_one_holder(settings: Settings) -> None:
    policy = CrawlPolicy()

    with worker_lock(settings, policy) as first, worker_lock(settings, policy) as second:
        assert first is not None and first()
        assert second is None

    with worker_lock(settings, policy) as again:
        assert again is not None
