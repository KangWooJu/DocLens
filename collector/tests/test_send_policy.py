"""host 상태 전이 표와 문서 응답 분류 표의 행마다"""

from datetime import UTC, datetime
from types import MappingProxyType

import pytest

from collector.config import CrawlPolicy
from collector.crawl.send_policy import (
    Accepted,
    HostState,
    Rejected,
    Response,
    Retry,
    SendOutcome,
    TransportFailed,
    classify,
    next_host_state,
)
from tests.support.fixture_site import site

POLICY = CrawlPolicy()
PAGE = site(pages=3)["/spring-boot/4.1/reference/index.html"]
GRADLE_PROPERTIES = b"# synthetic\nversion=4.1.2\n"


def response(
    status: int,
    body: bytes = PAGE,
    content_type: str = "text/html; charset=utf-8",
) -> Response:
    return Response(
        url="https://docs.spring.io/spring-boot/4.1/reference/index.html",
        status=status,
        headers=MappingProxyType({"content-type": content_type}),
        body=body,
        observed_at=datetime(2026, 11, 24, tzinfo=UTC),
    )


@pytest.mark.parametrize("status", [200, 301, 404, 408, 500])
def test_non_failure_resets_failures_and_closes_half_open(status: int) -> None:
    update = next_host_state(
        HostState(consecutive_failures=3, circuit_level=2, half_open=True, throttle_level=1),
        SendOutcome(status=status),
        POLICY,
    )

    assert update.state.consecutive_failures == 0
    assert (update.state.circuit_level, update.state.half_open) == (2, False)
    assert update.state.throttle_level == (0 if status == 200 else 1)
    assert (update.wait_seconds, update.open_seconds) == (2.0, None)


@pytest.mark.parametrize(
    "outcome",
    [
        SendOutcome(status=503),
        SendOutcome(failure="CONNECT"),
        SendOutcome(failure="TIMEOUT"),
        SendOutcome(failure="TLS"),
    ],
)
def test_failures_count_until_threshold_then_open_circuit(outcome: SendOutcome) -> None:
    state = HostState()

    for count in range(1, 5):
        update = next_host_state(state, outcome, POLICY)
        state = update.state
        assert (state.consecutive_failures, update.open_seconds) == (count, None)

    update = next_host_state(state, outcome, POLICY)

    assert update.state == HostState(consecutive_failures=0, circuit_level=1, half_open=True)
    assert update.open_seconds == 600.0


def test_half_open_failure_reopens_at_next_level_up_to_three() -> None:
    opened = [
        next_host_state(
            HostState(circuit_level=level, half_open=True),
            SendOutcome(status=502),
            POLICY,
        )
        for level in (1, 2, 3)
    ]

    assert [(update.state.circuit_level, update.open_seconds) for update in opened] == [
        (2, 1800.0),
        (3, 3600.0),
        (3, 3600.0),
    ]


def test_429_raises_throttle_level_and_keeps_longer_retry_after() -> None:
    state = HostState(consecutive_failures=2)
    waits = []

    for _ in range(5):
        update = next_host_state(state, SendOutcome(status=429), POLICY)
        state = update.state
        waits.append(update.wait_seconds)

    assert waits == [300.0, 600.0, 1200.0, 3600.0, 3600.0]
    assert state.consecutive_failures == 0
    assert next_host_state(HostState(), SendOutcome(status=429, retry_after=900.0), POLICY).wait_seconds == 900.0


@pytest.mark.parametrize("kind", ["DENIED", "INTERNAL"])
def test_denied_and_internal_do_not_touch_host_state(kind: str) -> None:
    state = HostState(consecutive_failures=4, circuit_level=1, half_open=True, throttle_level=2)
    update = next_host_state(state, SendOutcome(failure=kind), POLICY)

    assert (update.state, update.wait_seconds, update.open_seconds) == (state, 2.0, None)


def test_wait_uses_longer_crawl_delay_and_retry_after() -> None:
    assert next_host_state(HostState(), SendOutcome(status=200), POLICY, crawl_delay=1.0).wait_seconds == 2.0
    assert next_host_state(HostState(), SendOutcome(status=200), POLICY, crawl_delay=5.0).wait_seconds == 5.0
    assert next_host_state(HostState(), SendOutcome(status=503, retry_after=7.0), POLICY).wait_seconds == 7.0


def test_accepted_page_is_not_stored_yet() -> None:
    result = classify(
        response(200),
        1,
        POLICY,
        "html",
    )

    assert isinstance(result, Accepted)
    assert result.markers.version == "4.1.3"


def test_git_blob_text_is_accepted_with_text_markers() -> None:
    result = classify(
        response(
            200,
            GRADLE_PROPERTIES,
            "text/plain; charset=utf-8",
        ),
        1,
        POLICY,
        "git-blob",
    )

    assert isinstance(result, Accepted)
    assert (result.markers.version, result.markers.nav_hrefs) == ("4.1.2", ())


@pytest.mark.parametrize(
    ("given", "source_type"),
    [
        (response(200, b"", "text/plain"), "git-blob"),
        (response(200, b"version=4.1.2\n\xff", "text/plain"), "git-blob"),
        (response(200, GRADLE_PROPERTIES, "application/json"), "git-blob"),
        (response(200), "git-blob"),
        (response(200, content_type="text/plain"), "html"),
    ],
)
def test_body_must_match_the_source_type(
    given: Response,
    source_type: str,
) -> None:
    result = classify(
        given,
        1,
        POLICY,
        source_type,
    )

    assert isinstance(result, Rejected)
    assert (result.error_code, result.http_status) == ("INVALID_BODY", 200)


@pytest.mark.parametrize(
    ("given", "code"),
    [
        (response(200, b"<title>Access denied</title><article class='doc'>" + b"x" * 100), "INVALID_BODY"),
        (response(200, b"<article class='doc'>short</article>"), "INVALID_BODY"),
        (response(200, content_type="application/json"), "INVALID_BODY"),
        (response(304, b""), "INVALID_BODY"),
        (response(204, b""), "INVALID_BODY"),
        (response(301, b""), "REDIRECT_DENIED"),
        (response(302, b""), "REDIRECT_DENIED"),
        (response(400, b""), "HTTP_CLIENT"),
        (response(401, b""), "AUTH_REQUIRED"),
        (response(403, b""), "FORBIDDEN"),
        (response(404, b""), "NOT_FOUND"),
        (response(410, b""), "NOT_FOUND"),
        (response(418, b""), "HTTP_CLIENT"),
        (TransportFailed("TOO_LARGE"), "RESPONSE_TOO_LARGE"),
        (TransportFailed("ENCODING"), "INVALID_BODY"),
        (TransportFailed("TLS"), "HTTP_CLIENT"),
        (TransportFailed("DENIED"), "URL_DENIED"),
    ],
)
def test_rejected_rows(
    given: Response | TransportFailed,
    code: str,
) -> None:
    result = classify(
        given,
        1,
        POLICY,
        "html",
    )

    assert isinstance(result, Rejected)
    assert (result.error_code, result.needs_review) == (code, True)
    assert result.http_status == (None if isinstance(given, TransportFailed) else given.status)


@pytest.mark.parametrize(
    "given",
    [
        response(408, b""),
        response(429, b""),
        response(500, b""),
        response(503, b""),
        TransportFailed("CONNECT"),
        TransportFailed("TIMEOUT"),
    ],
)
def test_retryable_rows_wait_then_exhaust(given: Response | TransportFailed) -> None:
    first = classify(
        given,
        1,
        POLICY,
        "html",
    )
    second = classify(
        given,
        2,
        POLICY,
        "html",
    )
    last = classify(
        given,
        3,
        POLICY,
        "html",
    )

    assert isinstance(first, Retry) and 30.0 <= first.delay_seconds <= 40.0
    assert isinstance(second, Retry) and 60.0 <= second.delay_seconds <= 70.0
    assert isinstance(last, Rejected) and last.error_code == "HTTP_RETRY_EXHAUSTED"
