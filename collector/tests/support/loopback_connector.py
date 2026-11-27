"""시험 adapter: URL의 host와 path는 그대로, 연결만 loopback 평문"""

import threading
from collections.abc import Callable, Mapping
from urllib.parse import urlsplit

from urllib3.connection import HTTPConnection

from collector.crawl.transport_gate import (
    DeadlineWatchdog,
    RawResponse,
    exchange,
    remaining,
    transport_error,
)

LOOPBACK = ("127.0.0.1", "::1")


class LoopbackConnector:
    """운영 adapter와 같은 watchdog과 read1 응답, 127.0.0.1과 ::1만"""

    def __init__(
        self,
        port: int,
        address: str = "127.0.0.1",
    ) -> None:
        if address not in LOOPBACK:
            raise ValueError(f"loopback only: {address}")

        self._port = port
        self._address = address

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> RawResponse:
        parts = urlsplit(url)
        watchdog = DeadlineWatchdog(deadline)
        conn = HTTPConnection(
            self._address,
            self._port,
            timeout=remaining(connect_timeout, deadline),
        )
        watchdog.attach(conn)

        try:
            conn.connect()
        except Exception as error:
            watchdog.cancel()
            conn.close()
            raise transport_error(error, watchdog) from error

        return exchange(
            conn,
            watchdog,
            parts.hostname or "",
            parts.path or "/",
            headers,
            deadline,
            read_timeout,
        )


class PausingConnector:
    """지정 URL 연결 직전에 PAUSED 한 줄을 쓰고 프로세스가 죽을 때까지 대기"""

    def __init__(
        self,
        inner: LoopbackConnector,
        pause_url: str,
        notify: Callable[[str], None] = lambda line: print(line, flush=True),
    ) -> None:
        self._inner = inner
        self._pause_url = pause_url
        self._notify = notify

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> RawResponse:
        if url == self._pause_url:
            self._notify(f"PAUSED {url}")
            threading.Event().wait()

        return self._inner.open(
            url,
            headers,
            deadline,
            connect_timeout,
            read_timeout,
        )
