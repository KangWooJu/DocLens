"""trustme 로컬 TLS 서버로 PinnedHttpsConnector의 Host, SNI, 인증서, proxy 무시, 시간, 주소 순서"""

import ssl
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ipaddress import ip_address
from pathlib import Path

import pytest
import trustme

from collector.crawl.send_policy import TransportFailed
from collector.crawl.transport_gate import PinnedHttpsConnector

HOST = "docs.spring.io"
URL = f"https://{HOST}/spring-boot/4.1/reference/index.html"
BODY = b"<html>synthetic</html>"
HEADERS = {"User-Agent": "DocLens-Collector/test (+test@invalid.example)", "Accept-Encoding": "identity"}


class TlsServer:
    """mode: ok, stall(응답 없음), trickle(헤더를 0.2초마다 1바이트)"""

    def __init__(
        self,
        ca: trustme.CA,
        name: str,
        mode: str,
    ) -> None:
        self.hosts: list[str | None] = []
        self.server_names: list[str | None] = []
        self.released = threading.Event()
        context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        ca.issue_cert(name).configure_cert(context)
        context.sni_callback = lambda _socket, server_name, _context: self.server_names.append(server_name)
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler(mode))
        self._server.daemon_threads = True
        self._server.handle_error = lambda request, address: None
        self._server.socket = context.wrap_socket(self._server.socket, server_side=True)
        self.port = self._server.server_address[1]

    def _handler(self, mode: str) -> type[BaseHTTPRequestHandler]:
        server = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                server.hosts.append(self.headers.get("Host"))

                if mode == "stall":
                    server.released.wait()
                    return

                if mode == "trickle":
                    for byte in b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n":
                        if server.released.wait(0.2):
                            return

                        self.wfile.write(bytes([byte]))
                        self.wfile.flush()

                    return

                self.send_response(200)
                self.send_header("Content-Length", str(len(BODY)))
                self.end_headers()
                self.wfile.write(BODY)

            def log_message(
                self,
                format: str,
                *args: object,
            ) -> None:
                pass

        return Handler

    def __enter__(self) -> "TlsServer":
        threading.Thread(
            target=self._server.serve_forever,
            daemon=True,
        ).start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.released.set()
        self._server.shutdown()
        self._server.server_close()


@pytest.fixture(scope="module")
def ca() -> trustme.CA:
    return trustme.CA()


@pytest.fixture(scope="module")
def ca_file(
    ca: trustme.CA,
    tmp_path_factory: pytest.TempPathFactory,
) -> str:
    path = tmp_path_factory.mktemp("ca") / "ca.pem"
    ca.cert_pem.write_to_path(str(path))
    return str(path)


@contextmanager
def tls_server(
    ca: trustme.CA,
    name: str = HOST,
    mode: str = "ok",
) -> Iterator[TlsServer]:
    with TlsServer(ca, name, mode) as server:
        yield server


def connector(
    ca_file: str | Path,
    port: int,
    *addresses: str,
) -> PinnedHttpsConnector:
    return PinnedHttpsConnector(
        resolve=lambda host: tuple(ip_address(address) for address in addresses or ("127.0.0.1",)),
        ca_certs=str(ca_file),
        port=port,
    )


def fetch(
    pinned: PinnedHttpsConnector,
    deadline_seconds: float = 5.0,
    read_timeout: float = 2.0,
) -> tuple[int, bytes]:
    raw = pinned.open(
        URL,
        HEADERS,
        time.monotonic() + deadline_seconds,
        1.0,
        read_timeout,
    )

    try:
        body = b""

        while chunk := raw.read(65536, read_timeout):
            body += chunk

        return raw.status, body
    finally:
        raw.close()


def test_host_header_and_sni_are_the_original_host(
    ca: trustme.CA,
    ca_file: str,
) -> None:
    with tls_server(ca) as server:
        assert fetch(connector(ca_file, server.port)) == (200, BODY)

    assert server.hosts == [HOST]
    assert server.server_names == [HOST]


def test_certificate_for_another_name_is_a_tls_failure(
    ca: trustme.CA,
    ca_file: str,
) -> None:
    with tls_server(ca, name="other.example") as server, pytest.raises(TransportFailed) as failed:
        fetch(connector(ca_file, server.port))

    assert failed.value.kind == "TLS"
    assert server.hosts == []


def test_environment_proxy_is_ignored(
    ca: trustme.CA,
    ca_file: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:9")
    monkeypatch.setenv("https_proxy", "http://127.0.0.1:9")

    with tls_server(ca) as server:
        assert fetch(connector(ca_file, server.port)) == (200, BODY)


def test_stalled_server_hits_the_read_idle_timeout(
    ca: trustme.CA,
    ca_file: str,
) -> None:
    started = time.monotonic()

    with tls_server(ca, mode="stall") as server, pytest.raises(TransportFailed) as failed:
        fetch(
            connector(ca_file, server.port),
            deadline_seconds=10.0,
            read_timeout=0.3,
        )

    assert failed.value.kind == "TIMEOUT"
    assert time.monotonic() - started < 3.0


def test_trickling_headers_are_cut_at_the_total_deadline(
    ca: trustme.CA,
    ca_file: str,
) -> None:
    started = time.monotonic()

    with tls_server(ca, mode="trickle") as server, pytest.raises(TransportFailed) as failed:
        fetch(
            connector(ca_file, server.port),
            deadline_seconds=1.0,
            read_timeout=0.5,
        )

    assert failed.value.kind == "TIMEOUT"
    assert 0.9 <= time.monotonic() - started < 2.5


def test_connect_failure_moves_to_the_next_address_in_one_open(
    ca: trustme.CA,
    ca_file: str,
) -> None:
    with tls_server(ca) as server:
        assert fetch(connector(ca_file, server.port, "127.0.0.2", "127.0.0.1")) == (200, BODY)

    assert server.hosts == [HOST]


def test_slow_resolver_is_a_connect_failure() -> None:
    def slow(host: str) -> tuple:
        time.sleep(5)
        return ()

    started = time.monotonic()

    with pytest.raises(TransportFailed) as failed:
        fetch(PinnedHttpsConnector(resolve=slow), deadline_seconds=0.5)

    assert failed.value.kind == "CONNECT"
    assert time.monotonic() - started < 2.0
