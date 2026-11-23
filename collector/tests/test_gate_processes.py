"""여러 프로세스가 같은 gate 규칙을 지키는지: 전역 동시성 1, 응답 뒤 간격, 진행 중 송신 만료 회수"""

import signal
import subprocess
import time
from pathlib import Path

import psycopg

from collector.config import Settings
from tests.conftest import (
    ROOT,
    SCALED,
    approve_sources,
    connected_app,
    definition,
    registry_file,
    run_collector_command,
    subprocess_env,
)
from tests.support.fixture_server import serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector

BOOT = "spring-boot-4.1.3-reference"
FRAMEWORK = "spring-framework-7.0.1-reference"
PAGES = 150


def two_sites() -> dict[str, bytes]:
    return {**site(pages=PAGES), **site("spring-framework", "7.0.1", pages=3)}


def start(
    settings: Settings,
    *args: str | int,
) -> subprocess.Popen[str]:
    return subprocess.Popen(
        run_collector_command(*args),
        cwd=ROOT,
        env=subprocess_env(settings),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def finish(process: subprocess.Popen[str]) -> tuple[int, str]:
    out, err = process.communicate(timeout=180)
    assert process.returncode == 0, err
    return process.returncode, out


def submit_jobs(
    settings: Settings,
    tmp_path: Path,
    jobs: int,
) -> None:
    """boot source 승인과 job 접수, framework source는 BLOCKED로 import"""

    with connected_app(settings, LoopbackConnector(9)) as app:
        approve_sources(
            app,
            settings,
            tmp_path,
            definition(),
        )
        (tmp_path / "framework").mkdir()
        app.registry.import_file(registry_file(tmp_path / "framework", definition(FRAMEWORK, "7.0.1")))

        for _ in range(jobs):
            app.jobs.submit(app.registry.draft_request([BOOT]))


def assert_one_at_a_time(
    gaps: list[float],
    max_in_flight: int,
) -> None:
    assert max_in_flight == 1
    assert min(gaps) >= SCALED.min_interval * 0.9


def test_probes_beside_a_sending_job_never_overlap(
    settings: Settings,
    tmp_path: Path,
) -> None:
    submit_jobs(settings, tmp_path, 1)
    probes: list[subprocess.Popen[str]] = []

    with serving(two_sites(), delay=0.05) as server:
        sender = start(
            settings,
            "--port",
            server.port,
            "send-pages",
            PAGES - 1,
        )

        while sender.poll() is None or not probes:
            batch = [
                start(
                    settings,
                    "--port",
                    server.port,
                    "registry",
                    "probe",
                    FRAMEWORK,
                )
                for _ in range(3)
            ]
            probes += batch

            for process in batch:
                finish(process)

        finish(sender)

    framework_entry = "/spring-framework/7.0/reference/index.html"
    counts = server.counts()
    assert_one_at_a_time(server.gaps(), server.max_in_flight)
    assert counts[framework_entry] == len(probes) >= 3
    assert all(counts[f"/spring-boot/4.1/reference/p{index:03d}.html"] == 1 for index in range(PAGES - 1))


def test_two_jobs_in_two_processes_never_overlap(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    submit_jobs(settings, tmp_path, 2)

    with serving(two_sites(), delay=0.05) as server:
        senders = [
            start(
                settings,
                "--port",
                server.port,
                "send-pages",
                80,
            )
            for _ in range(2)
        ]

        for process in senders:
            finish(process)

    assert_one_at_a_time(server.gaps(), server.max_in_flight)
    assert server.total() == 1 + 2 * 80
    assert (
        pg
        .execute("SELECT count(DISTINCT job_id), count(*) FROM collector.http_sends")
        .fetchone()
    ) == (2, 1 + 2 * 80)


def test_killed_sender_leaves_a_send_that_expires_and_is_reclaimed(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    entry = "https://docs.spring.io/spring-boot/4.1/reference/index.html"

    with serving(site(pages=3)) as server:
        with connected_app(settings, LoopbackConnector(server.port)) as app:
            app.registry.import_file(registry_file(tmp_path, definition()))

        paused = start(
            settings,
            "--port",
            server.port,
            "--pause-url",
            entry,
            "registry",
            "probe",
            BOOT,
        )
        assert paused.stdout is not None
        assert paused.stdout.readline().strip() == f"PAUSED {entry}"
        paused.send_signal(signal.SIGKILL)
        paused.wait(10)
        started = time.monotonic()

        with connected_app(settings, LoopbackConnector(server.port)) as app:
            result = app.registry.probe(BOOT)

    elapsed = time.monotonic() - started
    assert result.state == "PASSED"
    assert SCALED.in_flight_expiry - 3.0 < elapsed < SCALED.in_flight_expiry + 5.0
    assert [record.path for record in server.records] == ["/robots.txt", "/spring-boot/4.1/reference/index.html"]
    assert (
        pg
        .execute("SELECT path, state FROM collector.http_sends ORDER BY send_id")
        .fetchall()
    ) == [
        ("/robots.txt", "SENT"),
        ("/spring-boot/4.1/reference/index.html", "UNKNOWN"),
        ("/spring-boot/4.1/reference/index.html", "SENT"),
    ]
