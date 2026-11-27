"""내부 HTTP 접수와 조회: commit 뒤 202, 재생, 인증 순서, 오류 형식, readyRun, 상주 worker, 토큰 설정"""

import hashlib
import json
import logging
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from collector.__main__ import main
from collector.api import CallerTokens, create_api
from collector.app import App, build_app
from collector.config import Settings
from collector.contract import schema
from collector.contract.canonical_json import content_hash
from collector.store.object_store import ObjectStore
from tests.conftest import (
    ROOT,
    approve_sources,
    definition,
    registry_file,
    run_collector_command,
    subprocess_env,
)
from tests.support.fixture_server import serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector

JOBS = "/internal/v1/collection-jobs"
SOURCE_ID = "spring-boot-4.1.3-reference"
SCHEDULER_TOKEN = secrets.token_urlsafe(32)
OPERATOR_TOKEN = secrets.token_urlsafe(32)


def digest(token: str) -> str:
    return (
        hashlib
        .sha256(token.encode("utf-8"))
        .hexdigest()
    )


CALLER_TOKENS = f"scheduler:{digest(SCHEDULER_TOKEN)},operator:{digest(OPERATOR_TOKEN)}"


def bearer(token: str = SCHEDULER_TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client(app: App) -> Iterator[TestClient]:
    api = create_api(
        app,
        CallerTokens.parse(CALLER_TOKENS),
    )

    with TestClient(api, raise_server_exceptions=False) as client:
        yield client


@pytest.fixture
def request_body(
    app: App,
    settings: Settings,
    tmp_path: Path,
) -> dict[str, object]:
    approve_sources(
        app,
        settings,
        tmp_path,
        definition(),
    )
    return app.registry.draft_request([SOURCE_ID]).to_json()


def post(
    client: TestClient,
    body: object,
    token: str = SCHEDULER_TOKEN,
) -> object:
    return client.post(
        JOBS,
        json=body,
        headers=bearer(token),
    )


def error_of(response: object, status: int) -> dict[str, object]:
    """contracts error 본문과 상태 코드 확인"""

    assert response.status_code == status, response.text
    body = response.json()
    schema.validate("error", body)
    return body


def job_rows(
    pg: psycopg.Connection,
    request_id: object,
) -> list[tuple[object, ...]]:
    return (
        pg
        .execute(
            "SELECT job_id, state FROM collector.jobs WHERE request_id = %s",
            (str(request_id),),
        )
        .fetchall()
    )


def test_202_is_sent_after_the_job_is_committed(
    client: TestClient,
    request_body: dict[str, object],
    pg: psycopg.Connection,
) -> None:
    response = post(client, request_body)

    assert response.status_code == 202
    accept = response.json()
    schema.validate("accept", accept)
    assert job_rows(pg, request_body["requestId"]) == [(UUID(accept["jobId"]), "QUEUED")]


def test_commit_failure_never_answers_202(
    client: TestClient,
    request_body: dict[str, object],
    pg: psycopg.Connection,
) -> None:
    pg.execute(
        """
        CREATE FUNCTION collector.test_refuse_commit() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'commit refused'; END $$
        """,
    )
    pg.execute(
        """
        CREATE CONSTRAINT TRIGGER test_refuse_commit AFTER INSERT ON collector.jobs
        DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION collector.test_refuse_commit()
        """,
    )

    try:
        response = post(client, request_body)
    finally:
        pg.execute("DROP TRIGGER test_refuse_commit ON collector.jobs")
        pg.execute("DROP FUNCTION collector.test_refuse_commit()")

    assert error_of(response, 500)["code"] == "INTERNAL_ERROR"
    assert job_rows(pg, request_body["requestId"]) == []


def test_replay_conflict_and_body_limit(
    client: TestClient,
    request_body: dict[str, object],
) -> None:
    first = post(client, request_body)
    second = post(client, request_body)

    assert (first.status_code, second.status_code) == (202, 200)
    assert first.json()["jobId"] == second.json()["jobId"]
    assert (first.json()["duplicate"], second.json()["duplicate"]) == (False, True)

    conflict = post(
        client,
        {**request_body, "requestedAt": "2026-01-01T00:00:00Z"},
    )
    body = error_of(conflict, 409)
    assert (body["code"], body["requestId"]) == ("ID_INPUT_CONFLICT", request_body["requestId"])

    padded = {**request_body, "padding": "x" * (64 * 1024)}
    assert error_of(post(client, padded), 413)["code"] == "VALIDATION_FAILED"

    # 길이 선언 없는 chunked 본문도 같은 상한
    chunked = client.post(
        JOBS,
        content=iter([json.dumps(padded).encode("utf-8")]),
        headers={**bearer(), "Content-Type": "application/json"},
    )
    assert error_of(chunked, 413)["code"] == "VALIDATION_FAILED"


def test_authentication_comes_before_the_body(
    client: TestClient,
    request_body: dict[str, object],
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    missing = client.post(JOBS, json=request_body)

    assert error_of(missing, 401)["code"] == "AUTH_REQUIRED"
    assert missing.headers["WWW-Authenticate"] == "Bearer"
    assert error_of(post(client, request_body, "wrong"), 401)["code"] == "AUTH_REQUIRED"

    broken = {"Content-Type": "application/json"}
    unauthenticated = client.post(
        JOBS,
        content=b"{not json",
        headers=broken,
    )
    assert error_of(unauthenticated, 401)["code"] == "AUTH_REQUIRED"

    invalid = client.post(
        JOBS,
        content=b"{not json",
        headers={**broken, **bearer()},
    )
    assert error_of(invalid, 400)["code"] == "VALIDATION_FAILED"
    assert error_of(post(client, {"schemaVersion": 1}), 400)["code"] == "VALIDATION_FAILED"

    assert error_of(post(client, request_body, OPERATOR_TOKEN), 403)["code"] == "FORBIDDEN"
    other = client.get(
        "/internal/v1/other",
        headers=bearer(),
    )
    assert error_of(other, 403)["code"] == "FORBIDDEN"
    assert error_of(client.get("/nowhere"), 404)["code"] == "NOT_FOUND"
    assert client.get("/healthz").status_code == 200
    assert post(client, request_body).status_code == 202
    assert SCHEDULER_TOKEN not in caplog.text and OPERATOR_TOKEN not in caplog.text


def test_replay_is_checked_before_the_registry(
    app: App,
    client: TestClient,
    request_body: dict[str, object],
) -> None:
    assert post(client, request_body).status_code == 202

    app.registry.revoke(SOURCE_ID, "tester")
    replay = post(client, request_body)

    assert replay.status_code == 200
    assert replay.json()["duplicate"] is True

    fresh = post(
        client,
        {**request_body, "requestId": str(uuid4())},
    )
    assert error_of(fresh, 422)["code"] == "SOURCE_UNAPPROVED"


def seal(
    pg: psycopg.Connection,
    job_id: str,
    key: str,
    body: bytes,
) -> None:
    """QUEUED job을 RUNNING을 거쳐 SUCCEEDED로, readyRun 기록은 body 기준"""

    pg.execute(
        """
        UPDATE collector.jobs
        SET state = 'RUNNING', lease_owner = gen_random_uuid(), lease_epoch = 1, attempt_count = 1,
            lease_until = clock_timestamp() + interval '1 minute'
        WHERE job_id = %s
        """,
        (job_id,),
    )
    pg.execute(
        """
        UPDATE collector.jobs
        SET state = 'SUCCEEDED', lease_until = NULL, result_key = %s, result_hash = %s, result_size = %s
        WHERE job_id = %s
        """,
        (
            key,
            content_hash(body),
            len(body),
            job_id,
        ),
    )


def test_ready_run_returns_the_stored_bytes(
    client: TestClient,
    request_body: dict[str, object],
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id = post(client, request_body).json()["jobId"]
    pending = client.get(
        f"{JOBS}/{job_id}/ready-run",
        headers=bearer(),
    )
    body = error_of(pending, 409)
    assert (body["code"], body["retryable"]) == ("INPUT_NOT_SEALED", True)

    stored = b'{"schemaVersion": 1, "synthetic": true}\n'
    key = f"manifests/test/{uuid4()}/ready-run.json"
    store.put_once(key, stored)
    seal(
        pg,
        job_id,
        key,
        stored,
    )
    status = client.get(
        f"{JOBS}/{job_id}",
        headers=bearer(),
    )
    assert status.status_code == 200
    schema.validate("status", status.json())
    result = status.json()["result"]

    ready = client.get(
        f"{JOBS}/{job_id}/ready-run",
        headers=bearer(),
    )
    assert ready.status_code == 200
    assert ready.headers["Content-Type"] == "application/json"
    assert (content_hash(ready.content), len(ready.content)) == (result["contentHash"], result["sizeBytes"])
    assert ready.content == stored

    missing = client.get(
        f"{JOBS}/{uuid4()}/ready-run",
        headers=bearer(),
    )
    assert error_of(missing, 404)["code"] == "NOT_FOUND"
    unknown = client.get(
        f"{JOBS}/{uuid4()}",
        headers=bearer(),
    )
    assert error_of(unknown, 404)["code"] == "NOT_FOUND"


def test_ready_run_with_a_changed_object_is_refused(
    client: TestClient,
    request_body: dict[str, object],
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id = post(client, request_body).json()["jobId"]
    key = f"manifests/test/{uuid4()}/ready-run.json"
    store.put_once(key, b"{}")
    seal(
        pg,
        job_id,
        key,
        b"{ }",
    )
    response = client.get(
        f"{JOBS}/{job_id}/ready-run",
        headers=bearer(),
    )

    assert error_of(response, 500)["code"] == "HASH_MISMATCH"


def test_unavailable_database_is_retryable(settings: Settings) -> None:
    app = build_app(settings, LoopbackConnector(9))
    app.close()
    api = create_api(
        app,
        CallerTokens.parse(CALLER_TOKENS),
    )

    with TestClient(api, raise_server_exceptions=False) as client:
        response = client.get(
            f"{JOBS}/{uuid4()}",
            headers=bearer(),
        )

    body = error_of(response, 503)
    assert (body["code"], body["retryable"]) == ("INTERNAL_ERROR", True)


def job_state(
    pg: psycopg.Connection,
    job_id: UUID,
) -> str:
    return (
        pg
        .execute(
            "SELECT state FROM collector.jobs WHERE job_id = %s",
            (job_id,),
        )
        .fetchone()
    )[0]


def test_serving_worker_waits_for_jobs_and_stops_on_sigterm(
    app: App,
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    approve_sources(
        app,
        settings,
        tmp_path,
        definition(),
    )

    with serving(site(pages=3)) as server:
        worker = subprocess.Popen(
            run_collector_command(
                "--port",
                server.port,
                "work",
                "--serve",
            ),
            cwd=ROOT,
            env=subprocess_env(settings),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

        try:
            # 대기 job 0개, 상주 worker는 idle_poll 여러 번 지나도 살아 있음
            time.sleep(3)
            assert worker.poll() is None

            job_id = app.jobs.submit(app.registry.draft_request([SOURCE_ID])).job_id
            deadline = time.monotonic() + 120

            while job_state(pg, job_id) != "SUCCEEDED" and time.monotonic() < deadline:
                time.sleep(0.5)

            assert job_state(pg, job_id) == "SUCCEEDED"
            assert worker.poll() is None
            worker.send_signal(signal.SIGTERM)
            _, err = worker.communicate(timeout=60)
        finally:
            if worker.poll() is None:
                worker.kill()
                worker.communicate()

    assert worker.returncode == 0, err


def test_caller_tokens_format() -> None:
    callers = CallerTokens.parse(CALLER_TOKENS)

    assert callers.caller(f"Bearer {SCHEDULER_TOKEN}") == "scheduler"
    assert callers.caller(f"bearer {OPERATOR_TOKEN}") == "operator"
    assert callers.caller(f"Basic {SCHEDULER_TOKEN}") is None
    assert callers.caller(None) is None

    for text in ("", " , ", "scheduler", f"scheduler:{'A' * 64}", f"scheduler:{'a' * 63}", "Scheduler:" + "a" * 64):
        with pytest.raises(ValueError):
            CallerTokens.parse(text)


@pytest.mark.parametrize("value", ["", "scheduler:not-hex"])
def test_api_command_refuses_bad_tokens(
    app: App,
    monkeypatch: pytest.MonkeyPatch,
    value: str,
) -> None:
    monkeypatch.setenv("COLLECTOR_DB_URL", "postgresql://unused.invalid/doclens")
    monkeypatch.setenv("DOCLENS_CALLER_TOKENS", value)

    assert main(["api"], app=app) == 2


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def collector_process(
    settings: Settings,
    tokens: str,
    *argv: str,
) -> subprocess.Popen[str]:
    """운영 진입점 python -m collector"""

    return subprocess.Popen(
        [sys.executable, "-m", "collector", *argv],
        cwd=ROOT,
        env={**subprocess_env(settings), "DOCLENS_CALLER_TOKENS": tokens},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def wait_healthy(port: int) -> None:
    deadline = time.monotonic() + 30

    while True:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=1) as response:
                assert response.status == 200
                return
        except OSError:
            if time.monotonic() > deadline:
                raise

            time.sleep(0.2)


def test_api_command_serves_and_stops_on_sigterm(settings: Settings) -> None:
    port = free_port()
    process = collector_process(
        settings,
        CALLER_TOKENS,
        "api",
        "--port",
        str(port),
    )

    try:
        wait_healthy(port)
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{JOBS}/{uuid4()}",
            headers=bearer(),
        )

        with pytest.raises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=5)

        assert raised.value.code == 404
        process.send_signal(signal.SIGTERM)
        out, err = process.communicate(timeout=60)
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate()

    # uvicorn은 graceful 종료를 마친 뒤 받은 신호를 다시 올려 신호 종료 코드로 끝남
    assert process.returncode == -signal.SIGTERM, err
    assert "Application shutdown complete" in err
    assert SCHEDULER_TOKEN not in out + err


def test_other_commands_ignore_bad_tokens(
    settings: Settings,
    tmp_path: Path,
) -> None:
    registry = registry_file(tmp_path, definition())

    for argv in (("registry", "import", str(registry)), ("work",)):
        process = collector_process(
            settings,
            "not-a-token-list",
            *argv,
        )
        _, err = process.communicate(timeout=60)

        assert process.returncode == 0, err
