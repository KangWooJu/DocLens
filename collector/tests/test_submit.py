"""request, submit, status 명령과 접수 멱등"""

import json
import threading
from pathlib import Path

import pytest

from collector.app import App, build_app
from collector.config import Settings
from collector.contract.collection_request import CollectionRequest
from tests.conftest import approve_sources, definition
from tests.support.cli import cli
from tests.support.loopback_connector import LoopbackConnector


def write(
    directory: Path,
    name: str,
    value: dict[str, object],
) -> Path:
    path = directory / name
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


@pytest.fixture
def request_body(
    app: App,
    settings: Settings,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> dict[str, object]:
    approve_sources(
        app,
        settings,
        tmp_path,
        definition(),
        definition(
            "spring-framework-7.0.2-reference",
            "7.0.2",
            required=False,
        ),
    )
    code, out, _ = cli(
        app,
        capsys,
        "request",
        "spring-boot-4.1.3-reference",
    )

    assert code == 0
    return out[0]


def test_same_file_twice_is_one_job(
    app: App,
    request_body: dict[str, object],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    path = write(
        tmp_path,
        "request.json",
        request_body,
    )
    first = cli(
        app,
        capsys,
        "submit",
        path,
    )
    second = cli(
        app,
        capsys,
        "submit",
        path,
    )

    assert first[0] == second[0] == 0
    assert first[1][0]["jobId"] == second[1][0]["jobId"]
    assert (first[1][0]["duplicate"], second[1][0]["duplicate"]) == (False, True)
    assert first[1][0]["state"] == "QUEUED"

    code, out, _ = cli(
        app,
        capsys,
        "status",
        first[1][0]["jobId"],
    )

    assert code == 0
    assert out[0]["state"] == "QUEUED"
    assert out[0]["attemptCount"] == 0
    assert out[0]["result"] is None


def test_same_request_id_with_other_body_conflicts(
    app: App,
    request_body: dict[str, object],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert cli(
        app,
        capsys,
        "submit",
        write(
            tmp_path,
            "a.json",
            request_body,
        ),
    )[0] == 0

    changed = {**request_body, "requestedAt": "2026-01-01T00:00:00Z"}
    code, _, err = cli(
        app,
        capsys,
        "submit",
        write(
            tmp_path,
            "b.json",
            changed,
        ),
    )

    assert code == 3
    assert err[0]["code"] == "ID_INPUT_CONFLICT"
    assert err[0]["requestId"] == request_body["requestId"]
    assert err[0]["traceId"] == request_body["traceId"]


def test_concurrent_submits_converge(
    settings: Settings,
    request_body: dict[str, object],
) -> None:
    request = CollectionRequest.parse(request_body)
    apps = [build_app(settings, LoopbackConnector(9)) for _ in range(8)]
    barrier = threading.Barrier(len(apps))
    accepts = []
    lock = threading.Lock()

    def submit(app: App) -> None:
        barrier.wait()
        accept = app.jobs.submit(request)

        with lock:
            accepts.append(accept)

    threads = [threading.Thread(target=submit, args=(app,)) for app in apps]

    try:
        for thread in threads:
            thread.start()

        for thread in threads:
            thread.join(30)
    finally:
        for app in apps:
            app.close()

    assert len(accepts) == 8
    assert len({accept.job_id for accept in accepts}) == 1
    assert [accept.duplicate for accept in accepts].count(False) == 1


@pytest.mark.parametrize(
    ("change", "code"),
    [
        ({"sourceRegistryHash": "sha256:" + "0" * 64}, "SOURCE_UNAPPROVED"),
        ({"requestKind": "RECHECK"}, "VALIDATION_FAILED"),
        ({"schemaVersion": 2}, "VALIDATION_FAILED"),
    ],
)
def test_rejected_requests_exit_2(
    app: App,
    request_body: dict[str, object],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    change: dict[str, object],
    code: str,
) -> None:
    exit_code, _, err = cli(
        app,
        capsys,
        "submit",
        write(
            tmp_path,
            "r.json",
            {**request_body, **change},
        ),
    )

    assert (exit_code, err[0]["code"]) == (2, code)


def test_unapproved_and_optional_only_inputs_exit_2(
    app: App,
    request_body: dict[str, object],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    boot = request_body["inputs"][0]
    framework = {
        **boot,
        "sourceId": "spring-framework-7.0.2-reference",
        "project": "spring-framework",
        "exactVersion": "7.0.2",
        "required": False,
    }
    cases = {
        "SOURCE_UNAPPROVED": [{**boot, "exactVersion": "4.1.4"}],
        "VALIDATION_FAILED": [framework],
        "APPLICABILITY_UNSUPPORTED": [{**boot, "applicability": "MINOR_LINE"}],
    }

    for expected, inputs in cases.items():
        code, _, err = cli(
            app,
            capsys,
            "submit",
            write(
                tmp_path,
                "r.json",
                {**request_body, "inputs": inputs},
            ),
        )

        assert (code, err[0]["code"]) == (2, expected)


def test_request_needs_approved_sources(
    app: App,
    request_body: dict[str, object],
    capsys: pytest.CaptureFixture[str],
) -> None:
    code, _, err = cli(
        app,
        capsys,
        "request",
        "spring-boot-4.1.4-reference",
    )

    assert code == 2
    assert err[0]["code"] == "SOURCE_UNAPPROVED"


def test_status_of_unknown_job_exits_4(
    app: App,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code, _, err = cli(
        app,
        capsys,
        "status",
        "00000000-0000-4000-8000-000000000000",
    )

    assert code == 4
    assert err[0]["code"] == "NOT_FOUND"
