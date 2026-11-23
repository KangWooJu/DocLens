"""모든 외부 HTTP의 유일한 통로: URL, 승인, robots, 예산, 전역 동시성 1, 간격, 서킷, 시도, SSRF, 크기, 시간"""

import http.client
import socket
import ssl
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime
from importlib.metadata import version
from ipaddress import IPv4Address, IPv6Address, ip_address
from types import MappingProxyType
from typing import Literal, Protocol
from urllib.parse import urlsplit
from uuid import UUID

import urllib3
from protego import Protego
from psycopg import errors
from psycopg_pool import ConnectionPool
from urllib3.connection import HTTPConnection, HTTPSConnection
from urllib3.exceptions import ConnectTimeoutError, NewConnectionError
from urllib3.util.ssl_match_hostname import CertificateError

from collector.config import CrawlPolicy
from collector.crawl.send_policy import (
    HostState,
    Response,
    SendOutcome,
    TransportFailed,
    next_host_state,
)
from collector.db import Conn
from collector.errors import RequestRejected
from collector.job.job_repository import JobRepository, Lease
from collector.registry.source_registry import SourceScope

type Purpose = Literal["ROBOTS", "DISCOVERY", "DOCUMENT"]

ROBOTS_TOKEN = "DocLens-Collector"
ROBOTS_PATH = "/robots.txt"
# robots 응답 중 캐시하는 것, 나머지는 RobotsUnreachable
ROBOTS_CACHED = frozenset({200, 404, 410})
READ_CHUNK = 65536

# 예약 TX 2단계: job 송신은 APPROVED, probe는 BLOCKED만
RESERVE_APPROVAL = """
SELECT approval_state
FROM collector.sources
WHERE source_id = %(source_id)s
  AND approval_state = CASE WHEN %(is_probe)s::boolean THEN 'BLOCKED' ELSE 'APPROVED' END
FOR SHARE
"""

# 예약 TX 3단계: http_sends보다 host 행을 먼저 잠금
RESERVE_HOST_INSERT = """
INSERT INTO collector.host_states (host) VALUES (%(host)s) ON CONFLICT DO NOTHING
"""

RESERVE_HOST_LOCK = """
SELECT consecutive_failures, circuit_level, half_open, throttle_level,
       robots_status, robots_body, robots_fetched_at,
       extract(epoch FROM next_allowed_at - clock_timestamp()) AS wait_left_seconds,
       extract(epoch FROM circuit_open_until - clock_timestamp()) AS circuit_left_seconds,
       extract(epoch FROM clock_timestamp() - robots_fetched_at) AS robots_age_seconds
FROM collector.host_states
WHERE host = %(host)s
FOR UPDATE
"""

# 예약 TX 4단계: 만료된 진행 중 송신 회수
RESERVE_EXPIRE = """
UPDATE collector.http_sends
SET state = 'UNKNOWN', finished_at = clock_timestamp()
WHERE state = 'SENDING'
  AND reserved_at < clock_timestamp() - make_interval(secs => %(in_flight_expiry_seconds)s)
"""

# 예약 TX 5단계: 문서 시도
RESERVE_ATTEMPT = """
UPDATE collector.collection_targets
SET state = 'SENDING', attempt_count = attempt_count + 1, sending_epoch = %(epoch)s
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND state = 'PENDING' AND attempt_count < attempt_cap
  AND coalesce(next_attempt_at, '-infinity') <= clock_timestamp()
RETURNING attempt_count
"""

# 시도를 차감하지 못한 이유: 상한 도달이면 true, 아직 기한 전이면 false
RESERVE_ATTEMPT_EXHAUSTED = """
SELECT attempt_count >= attempt_cap
FROM collector.collection_targets
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
"""

# 예약 TX 6단계: 송신 원장
RESERVE_SEND = """
INSERT INTO collector.http_sends
    (budget_limit, host, job_id, lease_epoch, probe_source, purpose, document_id, path)
VALUES (%(budget_limit)s, %(host)s, %(job_id)s, %(epoch)s,
        %(probe_source)s, %(purpose)s, %(document_id)s, %(path)s)
RETURNING send_id
"""

# 결과 TX 1단계: 예약 TX와 같은 순서로 host 행 먼저, 지금 상태를 읽음
RESULT_HOST_LOCK = """
SELECT consecutive_failures, circuit_level, half_open, throttle_level
FROM collector.host_states
WHERE host = %(host)s
FOR UPDATE
"""

RESULT_SENT = """
UPDATE collector.http_sends
SET state = 'SENT', http_status = %(status)s, failure_kind = %(kind)s, finished_at = clock_timestamp()
WHERE send_id = %(send_id)s AND state = 'SENDING'
"""

# 중단된 송신, host 상태는 갱신하지 않음
RESULT_UNKNOWN = """
UPDATE collector.http_sends
SET state = 'UNKNOWN', finished_at = clock_timestamp()
WHERE send_id = %(send_id)s AND state = 'SENDING'
"""

# 서킷 열림 시각과 간격은 PG 시계로 계산, 돌려주는 값은 응답 관측 시각
RESULT_HOST_UPDATE = """
UPDATE collector.host_states
SET next_allowed_at = clock_timestamp() + make_interval(secs => %(wait_seconds)s),
    consecutive_failures = %(failures)s,
    circuit_level = %(level)s,
    circuit_open_until = CASE WHEN %(open_seconds)s::float8 IS NULL THEN circuit_open_until
                              ELSE clock_timestamp() + make_interval(secs => %(open_seconds)s::float8) END,
    half_open = %(half_open)s,
    throttle_level = %(throttle)s,
    robots_status = coalesce(%(robots_status)s::integer, robots_status),
    robots_body = CASE WHEN %(robots_status)s::integer IS NULL THEN robots_body ELSE %(robots_body)s::text END,
    robots_fetched_at = CASE WHEN %(robots_status)s::integer IS NULL THEN robots_fetched_at ELSE clock_timestamp() END
WHERE host = %(host)s
RETURNING date_trunc('second', clock_timestamp())
"""

BUDGET_MESSAGE = "daily send budget"
ONE_IN_FLIGHT = "http_sends_one_in_flight"


class ConfigError(RequestRejected):
    """연락처 없이 외부 송신을 시작하려 함"""

    def __init__(self, message: str) -> None:
        super().__init__("VALIDATION_FAILED", message)


class UrlDenied(Exception):
    """URL 정책 위반 또는 비공인 주소"""


class SourceUnapproved(Exception):
    """예약 TX의 승인 재확인 실패"""


class RobotsDenied(Exception):
    pass


class RobotsUnreachable(Exception):
    pass


class AttemptsExhausted(Exception):
    pass


class NotDue(Exception):
    """문서의 다음 시도 기한 전"""


class AddressDenied(Exception):
    """DNS 결과에 공인 주소가 아닌 것이 섞임"""


@dataclass(frozen=True)
class Fetch:
    url: str
    purpose: Purpose
    source_id: str
    scope: SourceScope
    run_id: UUID | None = None
    document_id: str | None = None


@dataclass(frozen=True)
class Deferred:
    """None이면 다음 KST 00:00, 아니면 PG가 계산한 남은 초"""

    reason: Literal["BUDGET", "HOST"]
    delay_seconds: float | None


class RawResponse(Protocol):
    status: int
    headers: Mapping[str, str]

    def read(
        self,
        amount: int,
        timeout: float,
    ) -> bytes: ...

    def close(self) -> None: ...


class Connector(Protocol):
    """gate 내부 seam: 소켓 연결과 응답 스트림만, 정책 없음"""

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> RawResponse: ...


@dataclass(frozen=True)
class _Reserved:
    send_id: int
    crawl_delay: float | None


@dataclass(frozen=True)
class _Again:
    seconds: float


@dataclass(frozen=True)
class _NeedRobots:
    pass


@dataclass(frozen=True)
class _Received:
    status: int
    headers: Mapping[str, str]
    body: bytes


class _RobotsCached(Exception):
    """robots 조회 예약 중 캐시가 이미 채워짐, 송신하지 않음"""


class _Stop(Exception):
    """예약 TX를 rollback하고 돌려줄 결과"""

    def __init__(self, result: Deferred | _Again | _NeedRobots) -> None:
        super().__init__()
        self.result = result


def user_agent(contact: str) -> str:
    if not contact.strip():
        raise ConfigError("COLLECTOR_CONTACT is not set, external sends are refused")

    return f"{ROBOTS_TOKEN}/{version('doclens-collector')} (+{contact.strip()})"


class TransportGate:
    """예약 commit 뒤에만 송신하고, 예약된 송신 행은 같은 프로세스가 반드시 닫음"""

    def __init__(
        self,
        pool: ConnectionPool,
        policy: CrawlPolicy,
        connector: Connector,
        contact: str,
    ) -> None:
        self._pool = pool
        self._policy = policy
        self._connector = connector
        self._contact = contact
        self._jobs = JobRepository()

    def check_contact(self) -> None:
        """연락처가 없으면 송신 경로 시작 거부"""

        user_agent(self._contact)

    def send(
        self,
        lease: Lease | None,
        fetch: Fetch,
    ) -> Response | Deferred:
        """lease가 None이면 registry probe, 2xx부터 5xx까지 Response, 3xx는 따르지 않음"""

        agent = user_agent(self._contact)
        host, path = _checked_url(fetch)
        robots_tried = False

        while True:
            step = self._reserve(
                lease,
                fetch,
                host,
                path,
            )

            if isinstance(step, _Reserved):
                break

            if isinstance(step, Deferred):
                return step

            if isinstance(step, _Again):
                time.sleep(step.seconds)
                continue

            if robots_tried:
                raise RobotsUnreachable(host)

            robots = self._robots(lease, fetch)

            if isinstance(robots, Deferred):
                return robots

            robots_tried = robots

        deadline = time.monotonic() + self._policy.total_deadline
        outcome, received, error = SendOutcome(), None, None

        try:
            outcome, received, error = self._transfer(
                fetch,
                agent,
                deadline,
            )
        finally:
            observed_at = self._finish(
                host,
                step,
                outcome,
                received if fetch.purpose == "ROBOTS" else None,
            )

        if error is not None:
            raise error

        if outcome.failure == "DENIED":
            raise UrlDenied(f"{host} resolves to a non-public address")

        if outcome.failure is not None:
            raise TransportFailed(outcome.failure)

        assert received is not None and observed_at is not None
        return Response(
            url=fetch.url,
            status=received.status,
            headers=received.headers,
            body=received.body,
            observed_at=observed_at,
        )

    def _robots(
        self,
        lease: Lease | None,
        fetch: Fetch,
    ) -> Deferred | bool:
        """robots 조회 뒤 캐시됐으면 True, 429면 False로 다시 예약해 host 대기를 받음"""

        robots = Fetch(
            url=f"https://{urlsplit(fetch.url).hostname}{ROBOTS_PATH}",
            purpose="ROBOTS",
            source_id=fetch.source_id,
            scope=fetch.scope,
            run_id=fetch.run_id,
        )

        try:
            response = self.send(lease, robots)
        except _RobotsCached:
            return True
        except TransportFailed as error:
            raise RobotsUnreachable(f"robots.txt failed: {error.kind}") from error

        if isinstance(response, Deferred):
            return response

        if response.status == 429:
            return False

        if response.status not in ROBOTS_CACHED:
            raise RobotsUnreachable(f"robots.txt answered {response.status}")

        return True

    def _reserve(
        self,
        lease: Lease | None,
        fetch: Fetch,
        host: str,
        path: str,
    ) -> _Reserved | Deferred | _Again | _NeedRobots:
        policy = self._policy

        try:
            with self._pool.connection() as conn, conn.transaction():
                if lease is not None:
                    self._jobs.fence(conn, lease)

                approved = (
                    conn
                    .execute(
                        RESERVE_APPROVAL,
                        {"source_id": fetch.source_id, "is_probe": lease is None},
                    )
                    .fetchone()
                )

                if approved is None:
                    raise SourceUnapproved(fetch.source_id)

                conn.execute(RESERVE_HOST_INSERT, {"host": host})
                row = (
                    conn
                    .execute(RESERVE_HOST_LOCK, {"host": host})
                    .fetchone()
                )
                assert row is not None
                robots_status, robots_body = row[4], row[5]
                wait_left, circuit_left, robots_age = row[7], row[8], row[9]

                if circuit_left is not None and circuit_left > 0:
                    raise _Stop(Deferred("HOST", float(circuit_left)))

                if wait_left > 0:
                    raise _Stop(
                        _Again(float(wait_left))
                        if wait_left <= policy.inline_host_wait
                        else Deferred("HOST", float(wait_left))
                    )

                crawl_delay = self._check_robots(
                    fetch,
                    robots_status,
                    robots_body,
                    robots_age,
                )
                conn.execute(
                    RESERVE_EXPIRE,
                    {"in_flight_expiry_seconds": policy.in_flight_expiry},
                )

                if fetch.purpose == "DOCUMENT":
                    assert lease is not None
                    self._take_attempt(
                        conn,
                        lease,
                        fetch,
                    )

                (send_id,) = (
                    conn
                    .execute(
                        RESERVE_SEND,
                        {
                            "budget_limit": policy.daily_budget,
                            "host": host,
                            "job_id": None if lease is None else lease.job_id,
                            "epoch": None if lease is None else lease.epoch,
                            "probe_source": fetch.source_id if lease is None else None,
                            "purpose": fetch.purpose,
                            "document_id": fetch.document_id,
                            "path": path,
                        },
                    )
                    .fetchone()
                )
                return _Reserved(send_id, crawl_delay)
        except _Stop as stop:
            return stop.result
        except errors.UniqueViolation as error:
            # 다른 프로세스의 송신이 진행 중, 그 송신은 deadline 안에 끝나거나 만료로 회수됨
            if error.diag.constraint_name != ONE_IN_FLIGHT:
                raise

            return _Again(policy.in_flight_poll)
        except errors.CheckViolation as error:
            if not (error.diag.message_primary or "").startswith(BUDGET_MESSAGE):
                raise

            return Deferred("BUDGET", None)

    def _check_robots(
        self,
        fetch: Fetch,
        status: int | None,
        body: str | None,
        age: float | None,
    ) -> float | None:
        """robots 캐시가 없거나 지났으면 조회 요청, 금지면 RobotsDenied, crawl-delay를 돌려줌"""

        fresh = status is not None and age is not None and age <= self._policy.robots_ttl

        if fetch.purpose != "ROBOTS" and not fresh:
            raise _Stop(_NeedRobots())

        if fetch.purpose == "ROBOTS" and fresh:
            # 다른 프로세스가 기다리는 사이 먼저 받아 둠
            raise _RobotsCached

        if status != 200 or body is None:
            return None

        rules = Protego.parse(body)

        if fetch.purpose != "ROBOTS" and not rules.can_fetch(fetch.url, ROBOTS_TOKEN):
            raise RobotsDenied(fetch.url)

        delay = rules.crawl_delay(ROBOTS_TOKEN)
        return None if delay is None else float(delay)

    def _take_attempt(
        self,
        conn: Conn,
        lease: Lease,
        fetch: Fetch,
    ) -> None:
        params = {
            "run_id": fetch.run_id,
            "document_id": fetch.document_id,
            "epoch": lease.epoch,
        }

        if (
            conn
            .execute(RESERVE_ATTEMPT, params)
            .fetchone()
        ) is not None:
            return

        exhausted = (
            conn
            .execute(RESERVE_ATTEMPT_EXHAUSTED, params)
            .fetchone()
        )

        if exhausted is not None and exhausted[0]:
            raise AttemptsExhausted(fetch.document_id)

        raise NotDue(fetch.document_id)

    def _transfer(
        self,
        fetch: Fetch,
        agent: str,
        deadline: float,
    ) -> tuple[SendOutcome, _Received | None, Exception | None]:
        """예외를 던지지 않고 결과로 돌려줌, 예상 못 한 예외는 INTERNAL과 원래 예외"""

        robots = fetch.purpose == "ROBOTS"
        headers = {
            "User-Agent": agent,
            "Accept": "text/plain" if robots else "text/html",
            "Accept-Encoding": "identity",
        }

        try:
            raw = self._connector.open(
                fetch.url,
                headers,
                deadline,
                self._policy.connect_timeout,
                self._policy.read_timeout,
            )
        except AddressDenied:
            return SendOutcome(failure="DENIED"), None, None
        except TransportFailed as error:
            return SendOutcome(failure=error.kind), None, None
        except Exception as error:  # 버그, 원래 예외를 호출자에게 다시 던짐
            return SendOutcome(failure="INTERNAL"), None, error

        try:
            body = self._read(
                raw,
                self._policy.robots_max_bytes if robots else self._policy.html_max_bytes,
                deadline,
            )
        except TransportFailed as error:
            return SendOutcome(status=raw.status, failure=error.kind), None, None
        except Exception as error:  # 버그, 원래 예외를 호출자에게 다시 던짐
            return SendOutcome(status=raw.status, failure="INTERNAL"), None, error
        finally:
            raw.close()

        received = _Received(
            status=raw.status,
            headers=MappingProxyType(dict(raw.headers)),
            body=body,
        )
        return SendOutcome(status=raw.status, retry_after=_retry_after(raw.headers)), received, None

    def _read(
        self,
        raw: RawResponse,
        limit: int,
        deadline: float,
    ) -> bytes:
        """압축 거부, 상한 초과 중단, 읽기마다 남은 시간으로 timeout"""

        encoding = (
            raw.headers
            .get("content-encoding", "identity")
            .strip()
            .lower()
        )

        if encoding not in ("", "identity"):
            raise TransportFailed("ENCODING", f"content-encoding {encoding}")

        length = (
            raw.headers
            .get("content-length", "")
            .strip()
        )

        if length.isdigit() and int(length) > limit:
            raise TransportFailed("TOO_LARGE", f"content-length {length}")

        chunks: list[bytes] = []
        size = 0

        while True:
            remaining = deadline - time.monotonic()

            if remaining <= 0:
                raise TransportFailed("TIMEOUT", "total deadline")

            chunk = raw.read(READ_CHUNK, min(self._policy.read_timeout, remaining))

            if not chunk:
                return b"".join(chunks)

            size += len(chunk)

            if size > limit:
                raise TransportFailed("TOO_LARGE", f"body over {limit} bytes")

            chunks.append(chunk)

    def _finish(
        self,
        host: str,
        reserved: _Reserved,
        outcome: SendOutcome,
        robots: _Received | None,
    ) -> datetime | None:
        """결과 TX, fence 없음: lease를 잃어도 예산과 host 사실은 기록"""

        with self._pool.connection() as conn, conn.transaction():
            current = (
                conn
                .execute(RESULT_HOST_LOCK, {"host": host})
                .fetchone()
            )

            if outcome.unknown:
                conn.execute(RESULT_UNKNOWN, {"send_id": reserved.send_id})
                return None

            conn.execute(
                RESULT_SENT,
                {
                    "send_id": reserved.send_id,
                    "status": outcome.status,
                    "kind": outcome.failure,
                },
            )
            update = next_host_state(
                HostState(*current),
                outcome,
                self._policy,
                reserved.crawl_delay,
            )
            cached = robots is not None and robots.status in ROBOTS_CACHED and outcome.failure is None
            (observed_at,) = (
                conn
                .execute(
                    RESULT_HOST_UPDATE,
                    {
                        "host": host,
                        "wait_seconds": update.wait_seconds,
                        "failures": update.state.consecutive_failures,
                        "level": update.state.circuit_level,
                        "open_seconds": update.open_seconds,
                        "half_open": update.state.half_open,
                        "throttle": update.state.throttle_level,
                        "robots_status": robots.status if cached else None,
                        "robots_body": _robots_text(robots) if cached else None,
                    },
                )
                .fetchone()
            )
            return observed_at


def _checked_url(fetch: Fetch) -> tuple[str, str]:
    """https, 승인 host, 443, userinfo와 query 없음, 승인 path 또는 robots.txt"""

    try:
        parts = urlsplit(fetch.url)
        port = parts.port
    except ValueError as error:
        raise UrlDenied(fetch.url) from error

    host = parts.hostname or ""
    path = parts.path or "/"
    allowed_path = path == ROBOTS_PATH or any(path.startswith(prefix) for prefix in fetch.scope.allowed_path_prefixes)

    if (
        parts.scheme != "https"
        or host not in fetch.scope.allowed_hosts
        or parts.netloc.lower() not in (host, f"{host}:443")
        or port not in (None, 443)
        or parts.query
        or "?" in fetch.url
        or not allowed_path
        or (fetch.purpose == "ROBOTS") != (path == ROBOTS_PATH)
    ):
        raise UrlDenied(fetch.url)

    return host, path


def _retry_after(headers: Mapping[str, str]) -> float | None:
    """초 단위 값만, HTTP-date는 무시"""

    value = (
        headers
        .get("retry-after", "")
        .strip()
    )
    return float(value) if value.isdigit() else None


def _robots_text(robots: _Received | None) -> str | None:
    if robots is None or robots.status != 200:
        return None

    return (
        robots.body
        .decode("utf-8", errors="replace")
        .replace("\x00", "")
    )


def resolve_public(
    host: str,
    getaddrinfo: Callable[..., Sequence[tuple]] = socket.getaddrinfo,
) -> tuple[IPv4Address | IPv6Address, ...]:
    """하나라도 공인 주소가 아니면 AddressDenied, 아니면 IPv4 먼저 중복 없이"""

    addresses: list[IPv4Address | IPv6Address] = []

    for *_, sockaddr in getaddrinfo(
        host,
        443,
        type=socket.SOCK_STREAM,
    ):
        address = ip_address(str(sockaddr[0]).split("%")[0])
        mapped = address.ipv4_mapped if isinstance(address, IPv6Address) else None

        if not address.is_global or (mapped is not None and not mapped.is_global):
            raise AddressDenied(f"{host} resolves to {address}")

        if address not in addresses:
            addresses.append(address)

    if not addresses:
        raise socket.gaierror(f"{host} has no address")

    return tuple(
        sorted(
            addresses,
            key=lambda address: address.version,
        ),
    )


class DeadlineWatchdog:
    """deadline에 연결의 소켓을 닫아 연결, TLS, 헤더 단계의 대기를 끊음"""

    def __init__(self, deadline: float) -> None:
        self.fired = False
        self._conn: HTTPConnection | None = None
        self._timer = threading.Timer(max(deadline - time.monotonic(), 0.0), self._fire)
        self._timer.daemon = True

    def attach(self, conn: HTTPConnection) -> None:
        self._conn = conn
        self._timer.start()

    def cancel(self) -> None:
        self._timer.cancel()

    def _fire(self) -> None:
        self.fired = True
        sock = getattr(
            self._conn,
            "sock",
            None,
        )

        if sock is None:
            return

        with suppress(OSError):
            sock.shutdown(socket.SHUT_RDWR)

        with suppress(OSError):
            sock.close()


class StreamResponse:
    """urllib3 연결 하나의 응답, 읽기마다 소켓 timeout을 다시 걸고 recv 한 번 이하"""

    def __init__(
        self,
        conn: HTTPConnection,
        response: urllib3.HTTPResponse,
        watchdog: DeadlineWatchdog,
    ) -> None:
        self.status = response.status
        self.headers: Mapping[str, str] = MappingProxyType(
            {key.lower(): response.headers[key] for key in response.headers},
        )
        self._conn = conn
        self._response = response
        self._watchdog = watchdog

    def read(
        self,
        amount: int,
        timeout: float,
    ) -> bytes:
        if self._watchdog.fired:
            raise TransportFailed("TIMEOUT", "total deadline")

        try:
            if self._conn.sock is not None:
                self._conn.sock.settimeout(timeout)

            return self._response.read1(amount, decode_content=False)
        except Exception as error:
            raise transport_error(error, self._watchdog) from error

    def close(self) -> None:
        self._watchdog.cancel()
        self._conn.close()


def transport_error(
    error: Exception,
    watchdog: DeadlineWatchdog,
) -> Exception:
    """소켓과 urllib3 예외를 TransportFailed로, 모르는 예외는 그대로"""

    if isinstance(error, TransportFailed):
        return error

    if watchdog.fired:
        return TransportFailed("TIMEOUT", f"total deadline: {error}")

    if isinstance(error, NewConnectionError):
        return TransportFailed("CONNECT", str(error))

    if isinstance(error, (TimeoutError, urllib3.exceptions.TimeoutError)):
        return TransportFailed("TIMEOUT", str(error))

    if isinstance(error, (ssl.SSLError, urllib3.exceptions.SSLError, CertificateError)):
        return TransportFailed("TLS", str(error))

    if isinstance(error, (OSError, urllib3.exceptions.HTTPError, http.client.HTTPException)):
        return TransportFailed("CONNECT", str(error))

    return error


def remaining(
    limit: float,
    deadline: float,
) -> float:
    return max(min(limit, deadline - time.monotonic()), 0.001)


def exchange(
    conn: HTTPConnection,
    watchdog: DeadlineWatchdog,
    host: str,
    path: str,
    headers: Mapping[str, str],
    deadline: float,
    read_timeout: float,
) -> StreamResponse:
    """연결된 conn에 GET 하나를 보내고 헤더까지 받음, Host는 원래 host"""

    try:
        assert conn.sock is not None
        conn.sock.settimeout(remaining(read_timeout, deadline))
        conn.request(
            "GET",
            path,
            headers={"Host": host, **headers},
            preload_content=False,
        )
        response = conn.getresponse()
    except Exception as error:
        watchdog.cancel()
        conn.close()
        raise transport_error(error, watchdog) from error

    return StreamResponse(
        conn,
        response,
        watchdog,
    )


class PinnedHttpsConnector:
    """DNS 결과를 모두 검증하고 IPv4 먼저 차례로 IP 고정 연결, Host와 SNI와 인증서는 원래 host"""

    def __init__(
        self,
        resolve: Callable[[str], Sequence[IPv4Address | IPv6Address]] = resolve_public,
        ca_certs: str | None = None,
        port: int = 443,
    ) -> None:
        self._resolve = resolve
        self._ca_certs = ca_certs
        self._port = port

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> StreamResponse:
        parts = urlsplit(url)
        host = parts.hostname or ""
        addresses = self._resolve_within(host, remaining(connect_timeout, deadline))

        for address in addresses:
            watchdog = DeadlineWatchdog(deadline)
            conn = HTTPSConnection(
                host=str(address),
                port=self._port,
                timeout=remaining(connect_timeout, deadline),
                cert_reqs="CERT_REQUIRED",
                ca_certs=self._ca_certs,
                assert_hostname=host,
                server_hostname=host,
            )
            watchdog.attach(conn)

            try:
                conn.connect()
            except (NewConnectionError, ConnectTimeoutError) as error:
                # 연결 단계만 실패한 주소는 다음 주소로, 한 번의 open이 송신 하나
                watchdog.cancel()
                conn.close()

                if watchdog.fired or time.monotonic() >= deadline:
                    raise TransportFailed("TIMEOUT", str(error)) from error

                continue
            except Exception as error:
                watchdog.cancel()
                conn.close()
                raise transport_error(error, watchdog) from error

            return exchange(
                conn,
                watchdog,
                host,
                parts.path or "/",
                headers,
                deadline,
                read_timeout,
            )

        raise TransportFailed("CONNECT", f"no address of {host} accepted the connection")

    def _resolve_within(
        self,
        host: str,
        timeout: float,
    ) -> Sequence[IPv4Address | IPv6Address]:
        """getaddrinfo는 시간 제한이 없어 daemon 스레드에서, 넘으면 그 스레드는 버림"""

        result: dict[str, object] = {}

        def run() -> None:
            try:
                result["addresses"] = self._resolve(host)
            except Exception as error:  # 주 스레드가 분류
                result["error"] = error

        thread = threading.Thread(
            target=run,
            daemon=True,
        )
        thread.start()
        thread.join(timeout)

        if thread.is_alive():
            raise TransportFailed("CONNECT", f"resolving {host} timed out")

        error = result.get("error")

        if isinstance(error, socket.gaierror):
            raise TransportFailed("CONNECT", str(error)) from error

        if isinstance(error, Exception):
            raise error

        return result["addresses"]
