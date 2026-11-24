"""stdlib ThreadingHTTPServer: 경로별 요청 수, 동시 처리 최댓값, 응답 시각, 응답 순서 주입"""

import threading
import time
from collections import Counter
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# text/plain으로 답하는 경로 끝
TEXT_SUFFIXES = (".txt", ".properties")


@dataclass(frozen=True)
class Reply:
    """주입 응답, body가 None이면 사이트 본문"""

    status: int = 200
    headers: Mapping[str, str] = field(default_factory=dict)
    body: bytes | None = None
    delay: float = 0.0
    trickle: float | None = None       # 헤더 뒤 본문을 이 간격마다 1바이트씩
    stall: bool = False                 # 헤더를 보내지 않고 서버 종료까지 대기


@dataclass
class Record:
    path: str
    host: str | None
    user_agent: str | None
    started: float
    finished: float = 0.0


class FixtureServer:
    """경로별 Reply 목록은 앞에서부터 하나씩, 전역 순번별 Reply가 우선"""

    def __init__(
        self,
        site: Mapping[str, bytes],
        delay: float = 0.0,
    ) -> None:
        self.site = dict(site)
        self.delay = delay
        self.scripts: dict[str, list[Reply]] = {}
        self.by_number: dict[int, Reply] = {}
        self.records: list[Record] = []
        self.max_in_flight = 0
        self.hold_number: int | None = None
        self.held = threading.Event()
        self.held_path: str | None = None
        self._release = threading.Event()
        self._in_flight = 0
        self._lock = threading.Lock()
        self._closed = threading.Event()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self._server.daemon_threads = True
        self.port = self._server.server_address[1]

    def hold(self, number: int) -> None:
        """전역 순번 number인 요청을 붙잡고 held를 켬, release까지 응답하지 않음"""

        self.hold_number = number

    def release(self) -> None:
        self._release.set()

    def counts(self) -> Counter[str]:
        with self._lock:
            return Counter(record.path for record in self.records)

    def total(self) -> int:
        with self._lock:
            return len(self.records)

    def gaps(self) -> list[float]:
        """앞 응답이 끝난 뒤 다음 요청이 오기까지의 간격"""

        with self._lock:
            ordered = sorted(self.records, key=lambda record: record.started)

        return [later.started - earlier.finished for earlier, later in zip(ordered, ordered[1:], strict=False)]

    def _reply(
        self,
        path: str,
        number: int,
    ) -> Reply:
        with self._lock:
            if number in self.by_number:
                return self.by_number[number]

            script = self.scripts.get(path)

            if script:
                return script.pop(0)

        return Reply() if path in self.site else Reply(status=404, body=b"not found")

    def _handler(self) -> type[BaseHTTPRequestHandler]:
        server = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                record = Record(
                    path=self.path,
                    host=self.headers.get("Host"),
                    user_agent=self.headers.get("User-Agent"),
                    started=time.monotonic(),
                )

                with server._lock:
                    server.records.append(record)
                    number = len(server.records)
                    server._in_flight += 1
                    server.max_in_flight = max(server.max_in_flight, server._in_flight)

                try:
                    if number == server.hold_number:
                        server.held_path = self.path
                        server.held.set()
                        server._release.wait()

                    self._answer(server._reply(self.path, number))
                except OSError:
                    pass
                finally:
                    record.finished = time.monotonic()

                    with server._lock:
                        server._in_flight -= 1

            def _answer(self, reply: Reply) -> None:
                time.sleep(server.delay + reply.delay)

                if reply.stall:
                    server._closed.wait()
                    return

                body = reply.body if reply.body is not None else server.site.get(self.path, b"")
                content_type = "text/plain" if self.path.endswith(TEXT_SUFFIXES) else "text/html; charset=utf-8"
                self.send_response(reply.status)
                self.send_header("Content-Type", content_type)

                if reply.trickle is None:
                    self.send_header("Content-Length", str(len(body)))

                for name, value in reply.headers.items():
                    self.send_header(name, value)

                self.end_headers()

                if reply.trickle is None:
                    self.wfile.write(body)
                    return

                for byte in body:
                    if server._closed.wait(reply.trickle):
                        return

                    self.wfile.write(bytes([byte]))
                    self.wfile.flush()

            def log_message(
                self,
                format: str,
                *args: object,
            ) -> None:
                pass

        return Handler

    def start(self) -> None:
        threading.Thread(
            target=self._server.serve_forever,
            daemon=True,
        ).start()

    def stop(self) -> None:
        self._closed.set()
        self._release.set()
        self._server.shutdown()
        self._server.server_close()


@contextmanager
def serving(
    site: Mapping[str, bytes],
    delay: float = 0.0,
) -> Iterator[FixtureServer]:
    server = FixtureServer(site, delay)
    server.start()

    try:
        yield server
    finally:
        server.stop()
