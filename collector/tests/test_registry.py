"""registry import, probe, approve, revoke 명령"""

import hashlib
import json
from collections.abc import Callable, Mapping
from dataclasses import replace
from pathlib import Path

import psycopg
import pytest

from collector.app import App
from collector.config import Settings
from collector.crawl.transport_gate import RawResponse
from collector.store.object_store import ObjectStore
from tests.conftest import ROOT, approve_sources, connected_app, definition, record_probe, registry_file
from tests.support.cli import cli
from tests.support.fixture_server import Reply, serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector

SOURCE_ID = "spring-boot-4.1.3-reference"


def probe_state(pg: psycopg.Connection) -> tuple[str, str]:
    return (
        pg
        .execute(
            "SELECT probe_state, approval_state FROM collector.sources WHERE source_id = %s",
            (SOURCE_ID,),
        )
        .fetchone()
    )


def test_import_approve_revoke(
    app: App,
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code, out, _ = cli(
        app,
        capsys,
        "registry",
        "import",
        registry_file(tmp_path, definition()),
    )

    assert code == 0
    assert out[0]["approvalState"] == "BLOCKED"
    assert out[0]["definitionHash"].startswith("sha256:")

    code, _, err = cli(
        app,
        capsys,
        "registry",
        "approve",
        SOURCE_ID,
        "--by",
        "tester",
        "--parser-profile",
        "spring-antora-reference@2",
    )
    assert (code, err[0]["code"]) == (3, "VERSION_UNVERIFIED")

    record_probe(
        settings,
        SOURCE_ID,
        "4.1.3",
    )
    assert probe_state(pg) == ("PASSED", "BLOCKED")

    changed = {**definition(), "versionRuleId": "antora-meta-version-v2"}
    assert cli(
        app,
        capsys,
        "registry",
        "import",
        registry_file(tmp_path, changed),
    )[0] == 0
    assert probe_state(pg) == ("NONE", "BLOCKED")

    record_probe(
        settings,
        SOURCE_ID,
        "4.1.3",
    )
    code, out, _ = cli(
        app,
        capsys,
        "registry",
        "approve",
        SOURCE_ID,
        "--by",
        "tester",
        "--parser-profile",
        "spring-antora-reference@2",
    )
    assert (code, out[0]["approvalState"]) == (0, "APPROVED")

    assert cli(
        app,
        capsys,
        "registry",
        "import",
        registry_file(tmp_path, changed),
    )[0] == 0
    code, _, err = cli(
        app,
        capsys,
        "registry",
        "import",
        registry_file(tmp_path, definition()),
    )
    assert (code, err[0]["code"]) == (3, "ID_INPUT_CONFLICT")

    code, out, _ = cli(
        app,
        capsys,
        "request",
        SOURCE_ID,
    )
    request_path = tmp_path / "request.json"
    request_path.write_text(json.dumps(out[0]), encoding="utf-8")

    code, out, _ = cli(
        app,
        capsys,
        "registry",
        "revoke",
        SOURCE_ID,
        "--by",
        "tester",
    )
    assert (code, out[0]["approvalState"]) == (0, "REVOKED")
    assert cli(
        app,
        capsys,
        "registry",
        "revoke",
        SOURCE_ID,
        "--by",
        "tester",
    )[0] == 3

    code, _, err = cli(
        app,
        capsys,
        "request",
        SOURCE_ID,
    )
    assert (code, err[0]["code"]) == (2, "SOURCE_UNAPPROVED")

    code, _, err = cli(
        app,
        capsys,
        "submit",
        request_path,
    )
    assert (code, err[0]["code"]) == (2, "SOURCE_UNAPPROVED")


def test_import_rejects_invalid_files(
    app: App,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    broken = tmp_path / "broken.json"
    broken.write_text("{", encoding="utf-8")
    cases = [
        broken,
        registry_file(tmp_path, {**definition(), "redirectHosts": ["spring.io"]}),
        registry_file(
            tmp_path,
            definition(),
            definition(),
        ),
        tmp_path / "missing.json",
    ]

    for path in cases:
        code, _, err = cli(
            app,
            capsys,
            "registry",
            "import",
            path,
        )

        assert (code, err[0]["code"]) == (2, "VALIDATION_FAILED")


def test_approve_unknown_source_exits_2(
    app: App,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code, _, err = cli(
        app,
        capsys,
        "registry",
        "approve",
        "unknown",
        "--by",
        "tester",
        "--parser-profile",
        "spring-antora-reference@2",
    )

    assert (code, err[0]["code"]) == (2, "NOT_FOUND")


ENTRY_PATH = "/spring-boot/4.1/reference/index.html"


def probe(
    app: App,
    capsys: pytest.CaptureFixture[str],
    source_id: str = SOURCE_ID,
) -> tuple[int, list[dict[str, object]], list[dict[str, object]]]:
    return cli(
        app,
        capsys,
        "registry",
        "probe",
        source_id,
    )


def test_committed_registry_file_imports_blocked(
    app: App,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code, out, _ = cli(
        app,
        capsys,
        "registry",
        "import",
        ROOT / "registry" / "spring-boot-4.1.json",
    )

    assert code == 0
    assert [(row["sourceId"], row["approvalState"]) for row in out] == [(SOURCE_ID, "BLOCKED")]


def test_probe_passes_stores_entry_and_allows_approval(
    settings: Settings,
    store: ObjectStore,
    pg: psycopg.Connection,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with serving(site(pages=5)) as server, connected_app(settings, LoopbackConnector(server.port)) as app:
        app.registry.import_file(registry_file(tmp_path, definition()))
        code, out, _ = probe(app, capsys)
        approved = app.registry.approve(
            SOURCE_ID,
            "tester",
            "spring-antora-reference@2",
        )

    entry = server.site[ENTRY_PATH]
    assert code == 0
    assert out[0]["probeState"] == "PASSED"
    assert (out[0]["probeVersion"], out[0]["navCount"]) == ("4.1.3", 5)
    assert out[0]["raw"]["contentHash"] == "sha256:" + hashlib.sha256(entry).hexdigest()
    assert store.get(out[0]["raw"]["objectKey"]) == entry
    assert [record.path for record in server.records] == ["/robots.txt", ENTRY_PATH]
    assert approved.approval_state == "APPROVED"
    assert (
        pg
        .execute("SELECT count(*) FROM collector.http_sends WHERE job_id IS NULL AND probe_source = %s", (SOURCE_ID,))
        .fetchone()
    ) == (2,)


@pytest.mark.parametrize(
    ("served", "reply", "version"),
    [
        (site(version="4.1.4", pages=3), None, "4.1.4"),
        (site(pages=3), Reply(status=302, headers={"Location": "https://docs.spring.io/spring-boot/4.1/"}), None),
        (site(pages=3), Reply(status=503, body=b""), None),
    ],
)
def test_probe_fails_on_other_version_redirect_or_error(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    served: dict[str, bytes],
    reply: Reply | None,
    version: str | None,
) -> None:
    with serving(served) as server, connected_app(settings, LoopbackConnector(server.port)) as app:
        app.registry.import_file(registry_file(tmp_path, definition()))

        if reply is not None:
            server.scripts[ENTRY_PATH] = [reply]

        code, out, _ = probe(app, capsys)
        approve = cli(
            app,
            capsys,
            "registry",
            "approve",
            SOURCE_ID,
            "--by",
            "tester",
            "--parser-profile",
            "spring-antora-reference@2",
        )

    assert (code, out[0]["probeState"], out[0]["probeVersion"]) == (4, "FAILED", version)
    assert server.total() == 2
    assert probe_state(pg) == ("FAILED", "BLOCKED")
    assert approve[0] == 3


class RedefiningConnector:
    """entry 연결 직전에 정의를 다시 import"""

    def __init__(
        self,
        inner: LoopbackConnector,
        redefine: Callable[[], None],
    ) -> None:
        self._inner = inner
        self._redefine = redefine

    def open(
        self,
        url: str,
        headers: Mapping[str, str],
        deadline: float,
        connect_timeout: float,
        read_timeout: float,
    ) -> RawResponse:
        if url.endswith("index.html"):
            self._redefine()

        return self._inner.open(
            url,
            headers,
            deadline,
            connect_timeout,
            read_timeout,
        )


def test_probe_is_dropped_when_the_definition_changes_meanwhile(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    (tmp_path / "changed").mkdir()
    changed = registry_file(tmp_path / "changed", {**definition(), "versionRuleId": "antora-meta-version-v2"})

    with serving(site(pages=3)) as server:
        connector = RedefiningConnector(LoopbackConnector(server.port), lambda: app.registry.import_file(changed))

        with connected_app(settings, connector) as app:
            app.registry.import_file(registry_file(tmp_path, definition()))
            code, _, err = probe(app, capsys)

    assert (code, err[0]["code"]) == (3, "INVALID_TRANSITION")
    assert probe_state(pg) == ("NONE", "BLOCKED")


def test_probe_refuses_unknown_approved_and_contactless_runs(
    settings: Settings,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with serving(site(pages=3)) as server:
        with connected_app(settings, LoopbackConnector(server.port)) as app:
            unknown = probe(app, capsys, "unknown")
            approve_sources(
                app,
                settings,
                tmp_path,
                definition(),
            )
            approved = probe(app, capsys)

        with connected_app(replace(settings, contact=""), LoopbackConnector(server.port)) as app:
            app.registry.import_file(
                registry_file(tmp_path, definition("spring-framework-7.0.1-reference", "7.0.1")),
            )
            contactless = probe(app, capsys, "spring-framework-7.0.1-reference")

    assert (unknown[0], unknown[2][0]["code"]) == (2, "NOT_FOUND")
    assert (approved[0], approved[2][0]["code"]) == (3, "INVALID_TRANSITION")
    assert (contactless[0], contactless[2][0]["code"]) == (2, "VALIDATION_FAILED")
    assert server.total() == 0
