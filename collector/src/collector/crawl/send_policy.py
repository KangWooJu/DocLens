"""host 상태 전이와 문서 응답 분류 (순수 함수)"""

import random
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from collector.config import CrawlPolicy
from collector.crawl.page_markers import PageMarkers, read_markers

type FailureKind = Literal["CONNECT", "TLS", "TIMEOUT", "TOO_LARGE", "ENCODING", "DENIED", "INTERNAL"]

# host 연속 실패로 세는 응답과 송신 실패
FAILURE_STATUSES = frozenset({502, 503, 504})
FAILURE_KINDS = frozenset({"CONNECT", "TLS", "TIMEOUT"})
# host 상태를 바꾸지 않는 송신 실패
IGNORED_KINDS = frozenset({"DENIED", "INTERNAL"})
RETRY_STATUSES = frozenset({408, 429})
CLIENT_CODES = {
    400: "HTTP_CLIENT",
    401: "AUTH_REQUIRED",
    403: "FORBIDDEN",
    404: "NOT_FOUND",
    410: "NOT_FOUND",
}
FAILURE_CODES = {
    "TOO_LARGE": "RESPONSE_TOO_LARGE",
    "ENCODING": "INVALID_BODY",
    "TLS": "HTTP_CLIENT",
    "DENIED": "URL_DENIED",
}
MIN_MAIN_CHARS = 80


class TransportFailed(Exception):
    """응답을 받지 못했거나 받은 응답이 송신 상한을 넘음"""

    def __init__(
        self,
        kind: FailureKind,
        message: str = "",
    ) -> None:
        super().__init__(message or kind)
        self.kind = kind

    @property
    def retryable(self) -> bool:
        return self.kind in ("CONNECT", "TIMEOUT")


@dataclass(frozen=True)
class HostState:
    consecutive_failures: int = 0
    circuit_level: int = 0
    half_open: bool = False
    throttle_level: int = 0


@dataclass(frozen=True)
class SendOutcome:
    """송신 하나의 결과, 상태 코드도 실패 종류도 없으면 중단된 송신"""

    status: int | None = None
    failure: FailureKind | None = None
    retry_after: float | None = None

    @property
    def unknown(self) -> bool:
        return self.status is None and self.failure is None


@dataclass(frozen=True)
class HostUpdate:
    """결과 TX가 쓰는 값, open_seconds가 None이면 서킷 열림 시각 유지"""

    state: HostState
    wait_seconds: float
    open_seconds: float | None


@dataclass(frozen=True)
class Response:
    """리다이렉트를 따르지 않은 응답, url은 요청 URL과 같음"""

    url: str
    status: int
    headers: Mapping[str, str]
    body: bytes
    observed_at: datetime


@dataclass(frozen=True)
class Accepted:
    """저장해도 되는 200 응답, 아직 저장 전"""

    response: Response
    markers: PageMarkers


@dataclass(frozen=True)
class Retry:
    delay_seconds: float
    http_status: int | None
    error_code: str


@dataclass(frozen=True)
class Rejected:
    error_code: str
    needs_review: bool
    http_status: int | None


def next_host_state(
    current: HostState,
    outcome: SendOutcome,
    policy: CrawlPolicy,
    crawl_delay: float | None = None,
) -> HostUpdate:
    """서킷, 429 throttle, 간격"""

    wait = max(
        policy.min_interval,
        crawl_delay or 0.0,
        outcome.retry_after or 0.0,
    )

    if outcome.failure in IGNORED_KINDS:
        return HostUpdate(current, wait, None)

    if outcome.status == 429:
        level = min(current.throttle_level + 1, len(policy.throttle))
        return HostUpdate(
            HostState(
                consecutive_failures=0,
                circuit_level=current.circuit_level,
                half_open=current.half_open,
                throttle_level=level,
            ),
            max(wait, policy.throttle[level - 1]),
            None,
        )

    if outcome.status in FAILURE_STATUSES or outcome.failure in FAILURE_KINDS:
        return _failed(
            current,
            wait,
            policy,
        )

    succeeded = outcome.status is not None and 200 <= outcome.status < 300
    return HostUpdate(
        HostState(
            consecutive_failures=0,
            circuit_level=current.circuit_level,
            half_open=False,
            throttle_level=0 if succeeded else current.throttle_level,
        ),
        wait,
        None,
    )


def _failed(
    current: HostState,
    wait: float,
    policy: CrawlPolicy,
) -> HostUpdate:
    """연속 실패가 문턱에 닿거나 half-open 시험이 실패하면 다음 level로 서킷 열기"""

    failures = current.consecutive_failures + 1

    if not current.half_open and failures < policy.circuit_threshold:
        return HostUpdate(
            HostState(
                consecutive_failures=failures,
                circuit_level=current.circuit_level,
                half_open=False,
                throttle_level=current.throttle_level,
            ),
            wait,
            None,
        )

    level = min(current.circuit_level + 1, len(policy.circuit_open))
    return HostUpdate(
        HostState(
            consecutive_failures=0,
            circuit_level=level,
            half_open=True,
            throttle_level=current.throttle_level,
        ),
        wait,
        policy.circuit_open[level - 1],
    )


def classify(
    response: Response | TransportFailed,
    attempt: int,
    policy: CrawlPolicy,
) -> Accepted | Retry | Rejected:
    """문서 응답 판정, 원본 저장 전"""

    if isinstance(response, TransportFailed):
        if response.retryable:
            return _retry(
                attempt,
                None,
                policy,
            )

        return Rejected(FAILURE_CODES[response.kind], True, None)

    status = response.status

    if status == 200:
        markers = read_markers(response.body)

        if content_type(response.headers) == "text/html" and not markers.denied and (
            markers.main_chars >= MIN_MAIN_CHARS
        ):
            return Accepted(response, markers)

        return Rejected("INVALID_BODY", True, status)

    if status in RETRY_STATUSES or status >= 500:
        return _retry(
            attempt,
            status,
            policy,
        )

    if 300 <= status < 400 and status != 304:
        return Rejected("REDIRECT_DENIED", True, status)

    if status < 400:
        return Rejected("INVALID_BODY", True, status)

    return Rejected(CLIENT_CODES.get(status, "HTTP_CLIENT"), True, status)


def content_type(headers: Mapping[str, str]) -> str:
    """매개변수를 뺀 소문자 media type"""

    return (
        headers
        .get("content-type", "")
        .split(";")[0]
        .strip()
        .lower()
    )


def _retry(
    attempt: int,
    status: int | None,
    policy: CrawlPolicy,
) -> Retry | Rejected:
    if attempt >= policy.attempt_cap:
        return Rejected("HTTP_RETRY_EXHAUSTED", True, status)

    return Retry(
        policy.retry_delays[attempt - 1] + random.uniform(0.0, policy.retry_jitter),
        status,
        "HTTP_RETRY_EXHAUSTED",
    )
