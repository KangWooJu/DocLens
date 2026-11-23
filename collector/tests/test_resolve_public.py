"""getaddrinfo 결과를 주입한 공인 주소 검사"""

import socket
from ipaddress import ip_address

import pytest

from collector.crawl.transport_gate import AddressDenied, resolve_public


def answer(*addresses: str):
    def getaddrinfo(*args: object, **kwargs: object) -> list[tuple]:
        return [
            (
                socket.AF_INET6 if ":" in address else socket.AF_INET,
                socket.SOCK_STREAM,
                6,
                "",
                (address, 443),
            )
            for address in addresses
        ]

    return getaddrinfo


@pytest.mark.parametrize(
    "inner",
    ["127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "::ffff:127.0.0.1", "fc00::1", "0.0.0.0"],
)
def test_any_non_public_address_is_denied(inner: str) -> None:
    with pytest.raises(AddressDenied):
        resolve_public("docs.spring.io", answer("151.101.1.1", inner))


def test_public_addresses_ipv4_first_without_duplicates() -> None:
    resolved = resolve_public(
        "docs.spring.io",
        answer(
            "2606:4700::6810:1",
            "151.101.1.1",
            "2606:4700::6810:1",
            "151.101.65.1",
            "151.101.1.1",
        ),
    )

    assert resolved == tuple(
        ip_address(address) for address in ("151.101.1.1", "151.101.65.1", "2606:4700::6810:1")
    )


def test_empty_answer_is_a_resolution_failure() -> None:
    with pytest.raises(socket.gaierror):
        resolve_public("docs.spring.io", answer())
