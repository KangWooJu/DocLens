"""loopback fixture 서버로 gate의 송신 규칙: URL, robots, 3xx, 크기, 압축, 시간, DNS, 예외, 서킷, 승인, lease"""

import socket
import time
from collections.abc import Iterator, Mapping
from dataclasses import replace
from pathlib import Path

import psycopg
import pytest

from collector.app import App
from collector.config import Settings
from collector.crawl.send_policy import Rejected, Response, TransportFailed, classify
from collector.crawl.transport_gate import (
    AttemptsExhausted,
    ConfigError,
    Deferred,
    NotDue,
    PinnedHttpsConnector,
    RawResponse,
    RobotsDenied,
    RobotsUnreachable,
    SourceUnapproved,
    UrlDenied,
    resolve_public,
)
from collector.job.job_repository import LeaseLost
from tests.conftest import SCALED, claim, connected_app, insert_run, page
from tests.support.fixture_server import FixtureServer, Reply, serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector

ROOT = "https://docs.spring.io/spring-boot/4.1/reference/"
PATH = "/spring-boot/4.1/reference/"


@pytest.fixture
def server() -> Iterator[FixtureServer]:
    with serving(site(pages=60)) as server:
        yield server


@pytest.fixture
def gate_app(
    settings: Settings,
    server: FixtureServer,
) -> Iterator[App]:
    with connected_app(settings, LoopbackConnector(server.port)) as app:
        yield app


def sends(pg: psycopg.Connection) -> list[tuple[str, str, int | None, str | None]]:
    return (
        pg
        .execute("SELECT path, state, http_status, failure_kind FROM collector.http_sends ORDER BY send_id")
        .fetchall()
    )


def host_state(pg: psycopg.Connection) -> tuple[int, int, bool, int]:
    return (
        pg
        .execute(
            """
            SELECT consecutive_failures, circuit_level, half_open, throttle_level
            FROM collector.host_states WHERE host = 'docs.spring.io'
            """,
        )
        .fetchone()
    )


def seed_robots(pg: psycopg.Connection) -> None:
    """robots 조회 없이 '제한 없음' 캐시"""

    pg.execute(
        """
        INSERT INTO collector.host_states (host, robots_status, robots_fetched_at)
        VALUES ('docs.spring.io', 404, clock_timestamp())
        """,
    )


@pytest.mark.parametrize(
    "url",
    [
        "https://example.com/spring-boot/4.1/reference/p000.html",
        "http://docs.spring.io/spring-boot/4.1/reference/p000.html",
        "https://docs.spring.io:8443/spring-boot/4.1/reference/p000.html",
        "https://user@docs.spring.io/spring-boot/4.1/reference/p000.html",
        "https://docs.spring.io/spring-boot/4.1/reference/p000.html?lang=en",
        "https://docs.spring.io/spring-boot/4.0/reference/p000.html",
        "https://docs.spring.io/robots.txt",
    ],
)
def test_url_policy_denies_before_any_send(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
    url: str,
) -> None:
    job = claim(gate_app, settings, tmp_path)

    with pytest.raises(UrlDenied):
        gate_app.gate.send(job.lease, replace(page(job, "p000.html"), url=url))

    assert server.total() == 0
    assert sends(pg) == []


def test_robots_first_then_pages_with_headers(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    job = claim(gate_app, settings, tmp_path)

    first = gate_app.gate.send(job.lease, page(job, "p000.html"))
    second = gate_app.gate.send(job.lease, page(job, "p001.html"))

    assert isinstance(first, Response) and isinstance(second, Response)
    assert (first.status, first.body) == (200, server.site[PATH + "p000.html"])
    assert first.headers["content-type"].startswith("text/html")
    assert [record.path for record in server.records] == ["/robots.txt", PATH + "p000.html", PATH + "p001.html"]
    assert {record.host for record in server.records} == {"docs.spring.io"}
    assert server.records[0].user_agent.startswith("DocLens-Collector/") and "(+test@invalid.example)" in (
        server.records[0].user_agent
    )
    assert [row[:3] for row in sends(pg)] == [
        ("/robots.txt", "SENT", 200),
        (PATH + "p000.html", "SENT", 200),
        (PATH + "p001.html", "SENT", 200),
    ]
    assert min(server.gaps()) >= SCALED.min_interval * 0.9


def test_robots_disallow_stops_before_page_request(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    with (
        serving(site(pages=3, robots="User-agent: *\nDisallow: /spring-boot/\n")) as server,
        connected_app(settings, LoopbackConnector(server.port)) as app,
    ):
        job = claim(app, settings, tmp_path)

        with pytest.raises(RobotsDenied):
            app.gate.send(job.lease, page(job, "p000.html"))

        with pytest.raises(RobotsDenied):
            app.gate.send(job.lease, page(job, "p001.html"))

    assert [record.path for record in server.records] == ["/robots.txt"]
    assert len(sends(pg)) == 1


@pytest.mark.parametrize(
    ("reply", "allowed"),
    [
        (Reply(status=404, body=b""), True),
        (Reply(status=410, body=b""), True),
        (Reply(status=503, body=b""), False),
        (Reply(status=301, headers={"Location": "https://docs.spring.io/other.txt"}, body=b""), False),
        (Reply(status=403, body=b""), False),
        (Reply(body=b"x" * (SCALED.robots_max_bytes + 1)), False),
    ],
)
def test_robots_status_rules(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    tmp_path: Path,
    reply: Reply,
    allowed: bool,
) -> None:
    job = claim(gate_app, settings, tmp_path)
    server.scripts["/robots.txt"] = [reply]

    if allowed:
        assert gate_app.gate.send(job.lease, page(job, "p000.html")).status == 200
    else:
        with pytest.raises(RobotsUnreachable):
            gate_app.gate.send(job.lease, page(job, "p000.html"))

    assert server.counts()[PATH + "p000.html"] == (1 if allowed else 0)
    assert "/other.txt" not in server.counts()


def test_robots_429_throttles_the_host(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    job = claim(gate_app, settings, tmp_path)
    server.scripts["/robots.txt"] = [Reply(status=429, body=b"")]

    deferred = gate_app.gate.send(job.lease, page(job, "p000.html"))

    assert isinstance(deferred, Deferred) and deferred.reason == "HOST"
    assert SCALED.throttle[0] * 0.8 < deferred.delay_seconds <= SCALED.throttle[0]
    assert host_state(pg)[3] == 1
    assert server.total() == 1


def test_redirect_is_returned_not_followed(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    tmp_path: Path,
) -> None:
    job = claim(gate_app, settings, tmp_path)
    server.scripts[PATH + "p000.html"] = [Reply(status=301, headers={"Location": ROOT + "p001.html"}, body=b"")]

    response = gate_app.gate.send(job.lease, page(job, "p000.html"))

    assert isinstance(response, Response) and response.status == 301
    assert classify(
        response,
        1,
        SCALED,
        "html",
    ) == Rejected("REDIRECT_DENIED", True, 301)
    assert server.counts()[PATH + "p001.html"] == 0


@pytest.mark.parametrize(
    ("reply", "kind", "code"),
    [
        (Reply(body=b"x" * (9 * 1024 * 1024)), "TOO_LARGE", "RESPONSE_TOO_LARGE"),
        (Reply(headers={"Content-Encoding": "gzip"}), "ENCODING", "INVALID_BODY"),
        (Reply(trickle=0.2), "TIMEOUT", "HTTP_RETRY_EXHAUSTED"),
        (Reply(stall=True), "TIMEOUT", "HTTP_RETRY_EXHAUSTED"),
    ],
)
def test_size_encoding_and_time_limits(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
    reply: Reply,
    kind: str,
    code: str,
) -> None:
    job = claim(gate_app, settings, tmp_path)
    server.scripts[PATH + "p000.html"] = [reply]
    started = time.monotonic()

    with pytest.raises(TransportFailed) as failed:
        gate_app.gate.send(job.lease, page(job, "p000.html"))

    assert failed.value.kind == kind
    assert classify(
        failed.value,
        SCALED.attempt_cap,
        SCALED,
        "html",
    ).error_code == code
    assert time.monotonic() - started < SCALED.total_deadline + 1.0
    assert sends(pg)[-1][1:] == ("SENT", 200 if kind != "TIMEOUT" or reply.trickle else None, kind)


def test_document_send_takes_an_attempt(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    job = claim(gate_app, settings, tmp_path)
    run_id, _, (document_id, _) = insert_run(pg, job.lease.job_id)
    fetch = page(
        job,
        "p000.html",
        purpose="DOCUMENT",
        run_id=run_id,
        document_id=document_id,
    )

    assert gate_app.gate.send(job.lease, fetch).status == 200

    with pytest.raises(NotDue):
        gate_app.gate.send(job.lease, fetch)

    pg.execute(
        """
        UPDATE collector.collection_targets
        SET state = 'PENDING', sending_epoch = NULL, attempt_count = 3
        WHERE document_id = %s
        """,
        (document_id,),
    )

    with pytest.raises(AttemptsExhausted):
        gate_app.gate.send(job.lease, fetch)

    assert server.counts()[PATH + "p000.html"] == 1
    assert (
        pg
        .execute("SELECT count(*) FROM collector.http_sends WHERE purpose = 'DOCUMENT'")
        .fetchone()
    ) == (1,)


class RaisingConnector:
    def __init__(self, error: BaseException) -> None:
        self._error = error

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> RawResponse:
        raise self._error


def gaierror(host: str) -> tuple:
    raise socket.gaierror("synthetic resolution failure")


def slow_resolver(host: str) -> tuple:
    time.sleep(5)
    return ()


def private_resolver(host: str) -> tuple:
    return resolve_public(
        host,
        lambda *args, **kwargs: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.1", 443))],
    )


@pytest.mark.parametrize(
    ("connector", "raised", "kind"),
    [
        (PinnedHttpsConnector(resolve=gaierror), TransportFailed, "CONNECT"),
        (PinnedHttpsConnector(resolve=slow_resolver), TransportFailed, "CONNECT"),
        (PinnedHttpsConnector(resolve=private_resolver), UrlDenied, "DENIED"),
        (RaisingConnector(RuntimeError("bug")), RuntimeError, "INTERNAL"),
    ],
)
def test_failed_transfer_closes_the_send_row(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
    connector: object,
    raised: type[Exception],
    kind: str,
) -> None:
    seed_robots(pg)

    with connected_app(settings, connector) as app:
        job = claim(app, settings, tmp_path)

        with pytest.raises(raised):
            app.gate.send(job.lease, page(job, "p000.html"))

        with pytest.raises(raised):
            app.gate.send(job.lease, page(job, "p001.html"))

    assert [row[1:] for row in sends(pg)] == [("SENT", None, kind)] * 2
    assert host_state(pg)[0] == (2 if kind == "CONNECT" else 0)


def test_interrupted_transfer_is_closed_unknown(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    seed_robots(pg)

    with connected_app(settings, RaisingConnector(KeyboardInterrupt())) as app:
        job = claim(app, settings, tmp_path)

        with pytest.raises(KeyboardInterrupt):
            app.gate.send(job.lease, page(job, "p000.html"))

    assert [row[1:] for row in sends(pg)] == [("UNKNOWN", None, None)]
    assert host_state(pg) == (0, 0, False, 0)


def test_circuit_opens_after_global_requests_41_to_45_fail(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    policy = replace(SCALED, circuit_open=(2.0, 4.0, 6.0))
    deferrals = []

    with (
        serving(site(pages=60)) as server,
        connected_app(settings, LoopbackConnector(server.port), policy) as app,
    ):
        server.by_number = {number: Reply(status=502, body=b"") for number in range(41, 46)}
        job = claim(app, settings, tmp_path)

        for index in range(50):
            while isinstance(result := app.gate.send(job.lease, page(job, f"p{index:03d}.html")), Deferred):
                deferrals.append(result)
                time.sleep(result.delay_seconds)

    assert len(deferrals) == 1 and deferrals[0].reason == "HOST"
    assert 1.5 < deferrals[0].delay_seconds <= 2.0
    assert server.total() == 51 == len(sends(pg))
    assert [row[2:] for row in sends(pg)].count((502, None)) == 5
    assert host_state(pg) == (0, 1, False, 0)


def test_revoked_source_and_lost_lease_stop_before_sending(
    gate_app: App,
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    job = claim(gate_app, settings, tmp_path)
    gate_app.registry.revoke(job.sources[0].source_id, "tester")

    with pytest.raises(SourceUnapproved):
        gate_app.gate.send(job.lease, page(job, "p000.html"))

    with pytest.raises(SourceUnapproved):
        gate_app.gate.send(None, page(job, "p000.html"))

    pg.execute("UPDATE collector.jobs SET lease_until = clock_timestamp() - interval '1 second'")

    with pytest.raises(LeaseLost):
        gate_app.gate.send(job.lease, page(job, "p000.html"))

    assert server.total() == 0
    assert sends(pg) == []


def test_missing_contact_refuses_to_send(
    settings: Settings,
    server: FixtureServer,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    with connected_app(replace(settings, contact=" "), LoopbackConnector(server.port)) as app:
        job = claim(app, settings, tmp_path)

        with pytest.raises(ConfigError):
            app.gate.send(job.lease, page(job, "p000.html"))

    assert server.total() == 0
    assert sends(pg) == []
