"""registry import, approve, revoke 명령"""

import json
from pathlib import Path

import psycopg
import pytest

from collector.app import App
from collector.config import Settings
from tests.conftest import definition, record_probe, registry_file
from tests.support.cli import cli

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
