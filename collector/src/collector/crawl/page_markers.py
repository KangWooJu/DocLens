"""Antora 페이지 표지 추출, stdlib html.parser (순수 함수)"""

import re
from dataclasses import dataclass
from html.parser import HTMLParser

# 끝 태그가 없는 요소, 깊이 계수에서 제외
VOID = frozenset(
    {
        "area",
        "base",
        "br",
        "col",
        "embed",
        "hr",
        "img",
        "input",
        "link",
        "meta",
        "source",
        "track",
        "wbr",
    },
)
SOURCE_TAG = re.compile(r"^https://github\.com/spring-projects/[^/]+/blob/([^/]+)/")
# 차단, 쿠키 동의, captcha, 로그인 화면의 제목
DENIED_TITLE = re.compile(
    r"access denied|attention required|just a moment|captcha|cookies? (?:required|disabled)|log ?in|sign ?in",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class PageMarkers:
    version: str | None
    versioned_url: str | None
    source_tag: str | None
    nav_hrefs: tuple[str, ...]
    main_chars: int
    denied: bool


def read_markers(html: bytes) -> PageMarkers:
    """meta 버전, nav.nav-menu 링크, article.doc 글자 수, 편집 링크 tag, 차단 화면 여부"""

    parser = _MarkerParser()
    parser.feed(html.decode("utf-8", errors="replace"))
    parser.close()
    return PageMarkers(
        version=parser.meta.get("version"),
        versioned_url=parser.meta.get("versioned-url"),
        source_tag=parser.source_tag,
        nav_hrefs=tuple(parser.nav_hrefs),
        main_chars=parser.main_chars,
        denied=DENIED_TITLE.search(parser.title) is not None,
    )


class _MarkerParser(HTMLParser):
    """nav와 article 안의 열린 태그 깊이를 세어 범위를 판정"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.meta: dict[str, str] = {}
        self.nav_hrefs: list[str] = []
        self.source_tag: str | None = None
        self.main_chars = 0
        self.title = ""
        self._nav_depth = 0
        self._article_depth = 0
        self._in_title = False

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        values = {name: value or "" for name, value in attrs}
        classes = values.get("class", "").split()

        if tag == "meta" and values.get("name") in ("version", "versioned-url"):
            self.meta.setdefault(values["name"], values.get("content", ""))

        if tag == "a":
            self._link(values.get("href"))

        if tag == "title":
            self._in_title = True

        if tag in VOID:
            return

        if self._nav_depth:
            self._nav_depth += 1
        elif tag == "nav" and "nav-menu" in classes:
            self._nav_depth = 1

        if self._article_depth:
            self._article_depth += 1
        elif tag == "article" and "doc" in classes:
            self._article_depth = 1

    def handle_startendtag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        values = {name: value or "" for name, value in attrs}

        if tag == "meta" and values.get("name") in ("version", "versioned-url"):
            self.meta.setdefault(values["name"], values.get("content", ""))

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False

        if tag in VOID:
            return

        if self._nav_depth:
            self._nav_depth -= 1

        if self._article_depth:
            self._article_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title += data

        if self._article_depth:
            self.main_chars += len(data.strip())

    def _link(self, href: str | None) -> None:
        if href is None:
            return

        if self._nav_depth:
            self.nav_hrefs.append(href)

        match = SOURCE_TAG.match(href)

        if match is not None and self.source_tag is None:
            self.source_tag = match.group(1)
