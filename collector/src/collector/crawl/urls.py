"""링크 URL 정규화 (순수 함수)"""

import re
import unicodedata
from urllib.parse import urldefrag, urljoin, urlsplit

from collector.registry.source_registry import SourceScope

UNRESERVED = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
# path에 그대로 두는 ASCII 문자, 나머지와 짝 없는 %는 percent encode
PATH_SAFE = UNRESERVED | frozenset("!$&'()*+,;=:@/")
TOKEN = re.compile(r"%[0-9A-Fa-f]{2}|.", re.DOTALL)


def canonical_url(
    base: str,
    href: str,
    scope: SourceScope,
) -> str | None:
    """None이면 대상 아님, 링크의 http는 승인 host일 때 https로 표기 정규화"""

    url, _ = urldefrag(urljoin(base, href.strip()))

    if "?" in url:
        return None

    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return None

    host = _host(parts.hostname or "")

    if host is None or host not in scope.allowed_hosts:
        return None

    if parts.scheme not in ("https", "http") or parts.username is not None or port not in (None, 443):
        return None

    if parts.scheme == "http" and port is not None:
        return None

    path = _path(parts.path)

    if not any(path.startswith(prefix) for prefix in scope.allowed_path_prefixes):
        return None

    return f"https://{host}{path}"


def _host(raw: str) -> str | None:
    if not raw or raw.endswith("."):
        return None

    try:
        return (
            raw
            .encode("idna")
            .decode("ascii")
            .lower()
        )
    except UnicodeError:
        return None


def _path(raw: str) -> str:
    """percent hex 대문자, unreserved 해제, 비 ASCII는 NFC 뒤 UTF-8 percent encode, dot segment 해결"""

    encoded = "".join(
        _token(match.group(0))
        for match in TOKEN.finditer(unicodedata.normalize("NFC", raw))
    )
    return _remove_dot_segments(encoded or "/")


def _token(token: str) -> str:
    if len(token) == 3:
        char = chr(int(token[1:], 16))
        return char if char in UNRESERVED else token.upper()

    if token in PATH_SAFE:
        return token

    return "".join(f"%{byte:02X}" for byte in token.encode("utf-8"))


def _remove_dot_segments(path: str) -> str:
    output: list[str] = []

    for segment in path.split("/")[1:]:
        if segment == "..":
            if output:
                output.pop()
        elif segment != ".":
            output.append(segment)

    trailing = "/" if path.split("/")[-1] in (".", "..") else ""
    return "/" + "/".join(output) + trailing
