"""work 대기 루프: 다음 기한까지 잠, 종료 코드, 취소된 RUNNING, DB 오류 뒤 반납과 재claim, 이벤트 분리"""

import threading
import time
from collections.abc import Mapping
from contextlib import nullcontext
from dataclasses import replace
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest

from collector.app import App
from collector.config import Settings
from collector.crawl.crawl_facade import CrawlFacade
from collector.crawl.transport_gate import RawResponse
from collector.job.job_model import ClaimedJob, JobOutcome, Succeeded, Waiting
from collector.worker import HEARTBEAT_THREAD, Worker
from tests.conftest import SCALED, approve_sources, connected_app, definition
from tests.support.cli import cli
from tests.support.fixture_server import FixtureServer, serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector

SOURCE_ID = "spring-boot-4.1.3-reference"


def submit(
    app: App,
    settings: Settings,
    tmp_path: Path,
    jobs: int = 1,
) -> list[UUID]:
    approve_sources(
        app,
        settings,
        tmp_path,
        definition(),
    )
    return [app.jobs.submit(app.registry.draft_request([SOURCE_ID])).job_id for _ in range(jobs)]


def state(
    pg: psycopg.Connection,
    job_id: UUID,
) -> tuple[str, int, int]:
    return (
        pg
        .execute(
            "SELECT state, attempt_count, lease_epoch FROM collector.jobs WHERE job_id = %s",
            (job_id,),
        )
        .fetchone()
    )


def park(
    app: App,
    delay_seconds: float,
) -> None:
    """다른 owner가 claim해 RETRY_WAIT로 놓음"""

    job = app.jobs.claim(uuid4())
    assert job is not None
    app.jobs.finish(job.lease, Waiting("RETRY", delay_seconds))


def heartbeats() -> list[threading.Thread]:
    return [thread for thread in threading.enumerate() if thread.name == HEARTBEAT_THREAD and thread.is_alive()]


def test_sleeps_until_retry_wait_is_due_then_finishes(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    with serving(site(pages=3)) as server, connected_app(settings, LoopbackConnector(server.port)) as app:
        (job_id,) = submit(
            app,
            settings,
            tmp_path,
        )
        park(app, 1.5)
        started = time.monotonic()
        code = app.worker.run(None)

    assert code == 0
    assert time.monotonic() - started >= 1.4
    assert state(pg, job_id) == ("SUCCEEDED", 2, 2)


def test_exits_zero_without_pending_jobs(settings: Settings) -> None:
    with connected_app(settings, LoopbackConnector(9)) as app:
        assert app.worker.run(None) == 0


def test_exits_six_when_the_next_due_is_beyond_max_wait(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    with connected_app(settings, LoopbackConnector(9)) as app:
        (job_id,) = submit(
            app,
            settings,
            tmp_path,
        )
        park(app, 60.0)
        started = time.monotonic()
        code = app.worker.run(0.5)

    assert code == 6
    assert time.monotonic() - started < 5
    assert state(pg, job_id)[0] == "RETRY_WAIT"


def test_waits_for_a_cancelled_running_job_until_its_lease_expires(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    with connected_app(settings, LoopbackConnector(9)) as app:
        (job_id,) = submit(
            app,
            settings,
            tmp_path,
        )
        assert app.jobs.claim(uuid4()) is not None
        assert app.jobs.cancel(job_id).state == "RUNNING"
        # 죽은 worker가 남긴 살아 있는 lease
        pg.execute(
            "UPDATE collector.jobs SET lease_until = clock_timestamp() + interval '1.5 seconds' WHERE job_id = %s",
            (job_id,),
        )
        started = time.monotonic()
        code = app.worker.run(None)

    assert code == 0
    assert time.monotonic() - started >= 1.4
    assert state(pg, job_id)[0] == "CANCELLED"


def test_database_error_returns_the_lease_and_the_same_worker_reclaims(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    """결과 TX가 host 행에서 기다리는 동안 그 backend를 끊음"""

    terminated: list[int] = []

    def interfere(server: FixtureServer) -> None:
        assert server.held.wait(30)

        with (
            psycopg.connect(settings.db_url, autocommit=True) as other,
            psycopg.connect(settings.db_url, autocommit=True) as watcher,
            other.transaction(),
        ):
            other.execute("SELECT 1 FROM collector.host_states WHERE host = 'docs.spring.io' FOR UPDATE")
            server.release()
            deadline = time.monotonic() + 10

            while not terminated:
                assert time.monotonic() < deadline
                # TX 밖 연결이라 매번 새 통계 snapshot
                row = (
                    watcher
                    .execute(
                        """
                        SELECT pid FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE '%%host_states%%' AND pid <> %s
                        """,
                        (other.info.backend_pid,),
                    )
                    .fetchone()
                )

                if row is None:
                    time.sleep(0.02)
                    continue

                watcher.execute("SELECT pg_terminate_backend(%s)", (row[0],))
                terminated.append(row[0])

    with serving(site(pages=3)) as server, connected_app(settings, LoopbackConnector(server.port)) as app:
        (job_id,) = submit(
            app,
            settings,
            tmp_path,
        )
        # robots, entry 다음 첫 문서
        server.hold(3)
        helper = threading.Thread(target=interfere, args=(server,))
        helper.start()
        code = app.worker.run(None)
        helper.join(10)

    assert code == 0
    assert terminated
    assert heartbeats() == []
    assert state(pg, job_id) == ("SUCCEEDED", 2, 2)
    assert (
        pg
        .execute("SELECT count(*) FROM collector.http_sends WHERE state = 'UNKNOWN'")
        .fetchone()
    ) == (1,)


class LingeringSuccess:
    """첫 Succeeded 뒤 heartbeat가 lost를 켤 때까지 돌려주지 않는 시험 adapter"""

    def __init__(self, inner: CrawlFacade) -> None:
        self._inner = inner
        self.lost_seen = False

    def run(
        self,
        job: ClaimedJob,
        stop: threading.Event,
    ) -> JobOutcome | None:
        outcome = self._inner.run(job, stop)

        if isinstance(outcome, Succeeded) and not self.lost_seen:
            self.lost_seen = stop.wait(SCALED.heartbeat_interval * 4)

        return outcome


def test_heartbeat_lost_after_success_is_not_a_shutdown(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    with serving(site(pages=3)) as server, connected_app(settings, LoopbackConnector(server.port)) as app:
        job_ids = submit(
            app,
            settings,
            tmp_path,
            jobs=2,
        )
        crawl = LingeringSuccess(app.crawl)
        worker = Worker(
            app.jobs,
            crawl,
            SCALED,
            lambda: nullcontext(lambda: True),
        )
        code = worker.run(None)

    assert code == 0
    assert crawl.lost_seen
    assert [state(pg, job_id)[0] for job_id in job_ids] == ["SUCCEEDED", "SUCCEEDED"]


class BrokenConnector:
    """연결 adapter 안의 버그"""

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> RawResponse:
        raise RuntimeError("bug in the connector")


def test_unexpected_exception_fails_the_job_and_the_loop_goes_on(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    with connected_app(settings, BrokenConnector()) as app:
        (job_id,) = submit(
            app,
            settings,
            tmp_path,
        )
        code = app.worker.run(None)

    assert code == 0
    assert (
        pg
        .execute("SELECT state, last_error_code FROM collector.jobs WHERE job_id = %s", (job_id,))
        .fetchone()
    ) == ("FAILED_FINAL", "INTERNAL_ERROR")
    assert (
        pg
        .execute("SELECT state, failure_kind FROM collector.http_sends")
        .fetchall()
    ) == [("SENT", "INTERNAL")]


def test_work_refuses_to_start_without_a_contact(
    settings: Settings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with connected_app(replace(settings, contact=""), LoopbackConnector(9)) as app:
        code, out, err = cli(
            app,
            capsys,
            "work",
        )

    assert code == 2
    assert out == [] and err[0]["code"] == "VALIDATION_FAILED"
