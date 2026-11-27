"""합성 Antora 페이지와 text 원본의 표지 추출"""

import pytest

from collector.crawl.page_markers import read_markers, read_text_markers
from tests.support.fixture_site import site

ENTRY = "/spring-boot/4.1/reference/index.html"


def test_synthetic_page_markers() -> None:
    markers = read_markers(site(pages=5)[ENTRY])

    assert markers.version == "4.1.3"
    assert markers.versioned_url == "https://docs.spring.io/spring-boot/4.1/reference/index.html"
    assert markers.source_tag == "v4.1.3"
    assert markers.nav_hrefs == ("index.html", "p000.html", "p001.html", "p002.html", "p003.html")
    assert markers.main_chars >= 80
    assert not markers.denied


def test_links_outside_nav_and_text_outside_article_are_ignored() -> None:
    html = b"""<html><head><title>Page</title></head><body>
    <nav class="breadcrumbs"><a href="crumb.html">crumb</a></nav>
    <nav class="nav-menu"><ul><li><a href="a.html">A<br>a</a><img src="x.png"></li></ul></nav>
    <a href="after.html">after</a>
    <article class="doc"><p>short</p><div><p>text</p></div></article><p>outside article</p>
    </body></html>"""

    markers = read_markers(html)

    assert markers.nav_hrefs == ("a.html",)
    assert markers.main_chars == len("short") + len("text")
    assert markers.version is None and markers.source_tag is None


def test_denied_titles() -> None:
    for title in ("Access denied | docs.spring.io", "Just a moment...", "Sign in to continue", "Login"):
        assert read_markers(f"<title>{title}</title>".encode()).denied, title


def test_truncated_html_keeps_what_was_read() -> None:
    page = site(pages=5)[ENTRY]
    markers = read_markers(page[: page.index(b"p002.html")])

    assert markers.version == "4.1.3"
    assert markers.nav_hrefs == ("index.html", "p000.html", "p001.html")
    assert markers.main_chars == 0


def test_text_markers_read_the_first_version_line() -> None:
    body = "# version=0.0.1\n\nname=demo\nversion=4.1.2\nversion=9.9.9\n"
    markers = read_text_markers(body.encode())

    assert markers.version == "4.1.2"
    assert (markers.nav_hrefs, markers.main_chars, markers.denied) == ((), len(body), False)
    assert (markers.versioned_url, markers.source_tag) == (None, None)


@pytest.mark.parametrize(
    "body",
    [
        b"",
        b"name=demo\n",
        b"# version=4.1.2\n",
        b" version=4.1.2\n",
        b"version = 4.1.2\n",
    ],
)
def test_text_markers_without_a_version_line(body: bytes) -> None:
    assert read_text_markers(body).version is None
