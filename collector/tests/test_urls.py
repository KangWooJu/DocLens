"""contracts url-canonical-vectors의 canonical 값 전부"""

import json
from pathlib import Path

import pytest

from collector.crawl.urls import canonical_url
from collector.registry.source_registry import SourceScope

VECTORS = json.loads(
    (Path(__file__).resolve().parents[2] / "contracts" / "v1" / "url-canonical-vectors.json").read_text(
        encoding="utf-8",
    ),
)
SCOPE = SourceScope(
    allowed_hosts=tuple(VECTORS["scope"]["allowedHosts"]),
    allowed_path_prefixes=tuple(VECTORS["scope"]["allowedPathPrefixes"]),
)


@pytest.mark.parametrize(
    "vector",
    VECTORS["vectors"],
    ids=lambda vector: vector["name"],
)
def test_canonical_vector(vector: dict[str, str | None]) -> None:
    assert canonical_url(
        vector["base"],
        vector["href"],
        SCOPE,
    ) == vector["canonical"]


def test_canonical_url_is_a_fixed_point() -> None:
    for vector in VECTORS["vectors"]:
        canonical = vector["canonical"]

        if canonical is not None:
            assert canonical_url(
                canonical,
                canonical,
                SCOPE,
            ) == canonical
