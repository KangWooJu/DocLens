"""Protego robots 해석: 최장 일치, 와일드카드, crawl-delay"""

from protego import Protego

from collector.crawl.transport_gate import ROBOTS_TOKEN

URL = "https://docs.spring.io/spring-boot/4.1/reference/index.html"


def test_longest_match_wins_over_file_order() -> None:
    rules = Protego.parse("User-agent: *\nAllow: /spring-boot/\nDisallow: /spring-boot/4.1/\n")

    assert not rules.can_fetch(URL, ROBOTS_TOKEN)
    assert rules.can_fetch("https://docs.spring.io/spring-boot/4.0/index.html", ROBOTS_TOKEN)


def test_wildcard_and_end_anchor() -> None:
    rules = Protego.parse("User-agent: *\nDisallow: /*/reference/*.html$\n")

    assert not rules.can_fetch(URL, ROBOTS_TOKEN)
    assert rules.can_fetch("https://docs.spring.io/spring-boot/4.1/reference/", ROBOTS_TOKEN)


def test_group_for_our_token() -> None:
    rules = Protego.parse("User-agent: *\nAllow: /\n\nUser-agent: DocLens-Collector\nDisallow: /spring-boot/\n")

    assert not rules.can_fetch(URL, ROBOTS_TOKEN)


def test_crawl_delay_is_read() -> None:
    assert Protego.parse("User-agent: *\nCrawl-delay: 5\n").crawl_delay(ROBOTS_TOKEN) == 5.0
    assert Protego.parse("User-agent: *\nAllow: /\n").crawl_delay(ROBOTS_TOKEN) is None
