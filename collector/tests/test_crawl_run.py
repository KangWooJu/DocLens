"""오프라인 수집 회차: loopback fixture 서버와 실제 worker subprocess"""

import json
import queue
import signal
import subprocess
import threading
import time
from collections.abc import Mapping
from pathlib import Path
from uuid import UUID

import psycopg
import pytest

from collector.config import Settings
from collector.contract import schema
from collector.contract.canonical_json import content_hash
from collector.contract.ids import document_id
from collector.store.object_store import ObjectStore, raw_key
from tests.conftest import (
    ROOT,
    SCALED,
    approve_sources,
    connected_app,
    definition,
    run_collector_command,
    subprocess_env,
)
from tests.support.fixture_server import FixtureServer, Reply, serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector

SOURCE_ID = "spring-boot-4.1.3-reference"
ROOT_PATH = "/spring-boot/4.1/reference/"
HOST_URL = "https://docs.spring.io"
PAGES = 150


def submit(
    settings: Settings,
    tmp_path: Path,
) -> UUID:
    """source 승인과 job 접수"""

    with connected_app(settings, LoopbackConnector(9)) as app:
        approve_sources(
            app,
            settings,
            tmp_path,
            definition(),
        )
        return app.jobs.submit(app.registry.draft_request([SOURCE_ID])).job_id


def start(
    settings: Settings,
    server: FixtureServer,
    *args: str | int,
) -> subprocess.Popen[str]:
    """worker subprocess, args는 work 앞의 run_collector 선택지와 work 뒤 선택지"""

    return subprocess.Popen(
        run_collector_command(
            "--port",
            server.port,
            *args,
        ),
        cwd=ROOT,
        env=subprocess_env(settings),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def finish(process: subprocess.Popen[str]) -> tuple[int, str, str]:
    out, err = process.communicate(timeout=240)
    return process.returncode, out, err


def work(
    settings: Settings,
    server: FixtureServer,
    *args: str | int,
) -> tuple[int, str, str]:
    return finish(
        start(
            settings,
            server,
            *args,
        ),
    )


def job(
    pg: psycopg.Connection,
    job_id: UUID,
) -> dict[str, object]:
    row = (
        pg
        .execute(
            """
            SELECT state, attempt_count, lease_epoch, last_error_code, wait_reason, result_key, result_hash,
                   result_size, next_attempt_at
            FROM collector.jobs WHERE job_id = %s
            """,
            (job_id,),
        )
        .fetchone()
    )
    names = ("state", "attempts", "epoch", "error", "wait", "key", "hash", "size", "next_attempt_at")
    return dict(zip(
        names,
        row,
        strict=True,
    ))


def sends(pg: psycopg.Connection) -> int:
    return (
        pg
        .execute("SELECT count(*) FROM collector.http_sends")
        .fetchone()
    )[0]


def url_of(path: str) -> str:
    return HOST_URL + path


def doc_id(path: str) -> str:
    return document_id(
        "spring-boot",
        "4.1.3",
        "html",
        "REFERENCE",
        url_of(path),
    )


def page_paths(pages: Mapping[str, bytes]) -> list[str]:
    return [path for path in pages if path != "/robots.txt"]


def fetch_order(pages: Mapping[str, bytes]) -> list[str]:
    """worker가 문서를 보내는 순서: documentId 순"""

    return sorted(page_paths(pages), key=doc_id)


def expected(pages: Mapping[str, bytes]) -> dict[str, str]:
    """documentId별 원본 hash"""

    return {doc_id(path): content_hash(pages[path]) for path in page_paths(pages)}


def read_json(
    store: ObjectStore,
    ref: Mapping[str, object],
) -> object:
    body = store.get(str(ref["objectKey"]))
    assert body is not None
    assert content_hash(body) == ref["contentHash"] and len(body) == ref["sizeBytes"]
    return json.loads(body)


def read_lines(
    store: ObjectStore,
    ref: Mapping[str, object],
) -> list[dict[str, object]]:
    body = store.get(str(ref["objectKey"]))
    assert body is not None
    assert content_hash(body) == ref["contentHash"]
    return [json.loads(line) for line in body.splitlines()]


def ready_run(
    store: ObjectStore,
    state: Mapping[str, object],
) -> dict[str, object]:
    """readyRun과 참조 객체를 모두 읽어 schema와 hash를 확인, 문서별 결과 요약"""

    ready = read_json(
        store,
        {"objectKey": state["key"], "contentHash": state["hash"], "sizeBytes": state["size"]},
    )
    schema.validate("readyRun", ready)
    discovery = read_json(store, ready["discovery"])
    schema.validate("discovery", discovery)

    for line in read_lines(store, discovery["targetList"]):
        schema.validate("target", line)

    outcomes = []
    lease_epochs = []

    for ref in ready["envelopes"]:
        envelope = read_json(store, ref)
        schema.validate("manifestEnvelope", envelope)
        outcomes += read_lines(store, envelope["jsonl"])
        lease_epochs.append(envelope["leaseEpoch"])

    for item in ready["protectedObjects"]:
        body = store.get(item["objectRef"]["objectKey"])
        assert body is not None and content_hash(body) == item["objectRef"]["contentHash"]

    for line in outcomes:
        schema.validate("documentOutcome", line)

    return {
        "batches": len(discovery["batches"]),
        "targets": discovery["targetCount"],
        "raw": {line["documentId"]: line["raw"]["contentHash"] for line in outcomes if line["outcome"] == "SUCCESS"},
        "failed": {line["documentId"]: line for line in outcomes if line["outcome"] == "FAILED"},
        "readiness": ready["readinessSequence"],
        "lease_epochs": lease_epochs,
    }


def test_crawl_run_end_to_end(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id = submit(settings, tmp_path)
    pages = site(pages=PAGES)

    with serving(pages) as server:
        code, out, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert state["state"] == "SUCCEEDED" and state["attempts"] == 1
    assert json.loads(out.splitlines()[-1])["state"] == "SUCCEEDED"
    summary = ready_run(store, state)
    assert summary["batches"] == 2 and summary["targets"] == PAGES
    assert summary["raw"] == expected(pages)
    assert server.total() == 1 + 1 + PAGES == sends(pg)


def targets(
    pg: psycopg.Connection,
    job_id: UUID,
) -> dict[str, tuple[str, int, str | None]]:
    """documentId별 상태, 시도, 오류 코드"""

    rows = (
        pg
        .execute(
            """
            SELECT t.document_id, t.state, t.attempt_count, t.error_code
            FROM collector.collection_targets t JOIN collector.collection_runs r USING (run_id)
            WHERE r.job_id = %s
            """,
            (job_id,),
        )
        .fetchall()
    )
    return {row[0]: row[1:] for row in rows}


def test_retry_then_success_and_exhausted_retries(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id = submit(settings, tmp_path)
    pages = site(pages=PAGES)
    # 앞쪽 두 문서라 재시도 사이에 다른 문서의 200이 끼어 서킷 문턱에 닿지 않음
    recovered, exhausted = fetch_order(pages)[:2]

    with serving(pages) as server:
        server.scripts[recovered] = [Reply(status=503), Reply(status=503)]
        server.scripts[exhausted] = [Reply(status=503), Reply(status=503), Reply(status=503)]
        code, _, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert state["state"] == "SUCCEEDED" and state["attempts"] == 1
    rows = targets(pg, job_id)
    assert rows[doc_id(recovered)] == ("SUCCEEDED", 3, None)
    assert rows[doc_id(exhausted)] == ("FAILED", 3, "HTTP_RETRY_EXHAUSTED")
    summary = ready_run(store, state)
    failed = summary["failed"][doc_id(exhausted)]
    assert failed["httpStatus"] == 503 and failed["attemptCount"] == 3 and failed["observedAt"]
    assert server.total() == 152 + 2 + 2 == sends(pg)


def test_circuit_opens_after_five_failures_and_resumes_after_wait(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id = submit(settings, tmp_path)
    pages = site(pages=PAGES)

    with serving(pages) as server:
        # 전역 요청 순번 41~45, robots와 entry 포함
        server.by_number = {number: Reply(status=502) for number in range(41, 46)}
        code, _, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["attempts"], state["epoch"]) == ("SUCCEEDED", 2, 2)
    rows = targets(pg, job_id)
    assert {row[0] for row in rows.values()} == {"SUCCEEDED"}
    assert sum(row[1] for row in rows.values()) == PAGES + 5
    assert ready_run(store, state)["raw"] == expected(pages)
    assert server.total() == 157 == sends(pg)
    assert (
        pg
        .execute("SELECT count(*) FROM collector.http_sends WHERE http_status = 502 AND failure_kind IS NULL")
        .fetchone()
    ) == (5,)


def test_entry_version_mismatch_stops_before_any_document(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    job_id = submit(settings, tmp_path)
    pages = site(pages=PAGES)
    entry = ROOT_PATH + "index.html"
    pages[entry] = pages[entry].replace(b'name="version" content="4.1.3"', b'name="version" content="4.1.4"')

    with serving(pages) as server:
        code, _, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["error"]) == ("NEEDS_REVIEW", "VERSION_UNVERIFIED")
    assert server.counts() == {"/robots.txt": 1, entry: 1}


def test_nav_drift_blocks_ready_run_and_resume(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    job_id = submit(settings, tmp_path)
    pages = site(pages=PAGES)
    drifted = ROOT_PATH + "p079.html"
    pages[drifted] = pages[drifted].replace(
        b"</ul>\n</nav>",
        b'<li class="nav-item"><a class="nav-link" href="added.html">added</a></li>\n</ul>\n</nav>',
    )

    with serving(pages) as server:
        code, _, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["error"], state["key"]) == ("NEEDS_REVIEW", "SCOPE_MISMATCH", None)
    assert server.total() == 152

    with connected_app(settings, LoopbackConnector(9)) as app, pytest.raises(Exception) as refused:
        app.jobs.resume(job_id)

    assert getattr(
        refused.value,
        "code",
        None,
    ) == "SCOPE_MISMATCH"


def interrupt(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
    action: str,
) -> tuple[UUID, FixtureServer, tuple[int, str, str]]:
    """20번째 요청을 붙잡은 동안 action을 하고 놓아줌"""

    job_id = submit(settings, tmp_path)

    with serving(site(pages=PAGES)) as server:
        server.hold(20)
        process = start(
            settings,
            server,
            "work",
        )
        assert server.held.wait(60)

        if action == "sigterm":
            process.send_signal(signal.SIGTERM)
        else:
            with connected_app(settings, LoopbackConnector(9)) as app:
                if action == "cancel":
                    app.jobs.cancel(job_id)
                else:
                    app.registry.revoke(SOURCE_ID, "tester")

        server.release()
        result = finish(process)

    return job_id, server, result


def test_cancel_while_running_stops_at_next_fence(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    job_id, server, (code, _, err) = interrupt(
        settings,
        tmp_path,
        pg,
        "cancel",
    )

    assert code == 0, err
    assert job(pg, job_id)["state"] == "CANCELLED"
    assert server.total() == 20 == sends(pg)


def test_revoke_while_running_stops_at_next_reservation(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    job_id, server, (code, _, err) = interrupt(
        settings,
        tmp_path,
        pg,
        "revoke",
    )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["error"]) == ("NEEDS_REVIEW", "SOURCE_UNAPPROVED")
    assert server.total() == 20 == sends(pg)


def test_sigterm_parks_the_job_and_next_work_finishes_it(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id, _, (code, _, err) = interrupt(
        settings,
        tmp_path,
        pg,
        "sigterm",
    )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["wait"]) == ("RETRY_WAIT", "SHUTDOWN")

    pages = site(pages=PAGES)

    with serving(pages) as server:
        code, _, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["attempts"]) == ("SUCCEEDED", 2)
    assert ready_run(store, state)["raw"] == expected(pages)
    assert server.total() == PAGES - 18
    assert sends(pg) == 152


def test_budget_exhaustion_parks_the_job_until_kst_midnight(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    job_id = submit(settings, tmp_path)

    with serving(site(pages=PAGES)) as server:
        code, _, err = work(
            settings,
            server,
            "--budget",
            3,
            "work",
            "--max-wait",
            1,
        )

    assert code == 6, err
    state = job(pg, job_id)
    assert (state["state"], state["wait"]) == ("RETRY_WAIT", "BUDGET")
    assert server.total() == 3 == sends(pg)
    assert (
        pg
        .execute(
            """
            SELECT next_attempt_at = (date_trunc('day', clock_timestamp() AT TIME ZONE 'Asia/Seoul') + interval '1 day')
                                     AT TIME ZONE 'Asia/Seoul'
            FROM collector.jobs WHERE job_id = %s
            """,
            (job_id,),
        )
        .fetchone()
    ) == (True,)


def paused(
    process: subprocess.Popen[str],
    timeout: float = 120,
) -> str:
    """stdout에서 PAUSED 줄을 기다림"""

    lines: queue.Queue[str] = queue.Queue()

    def read() -> None:
        for line in process.stdout:
            lines.put(line)

            if line.startswith("PAUSED"):
                return

    (
        threading
        .Thread(
            target=read,
            daemon=True,
        )
        .start()
    )
    deadline = time.monotonic() + timeout

    while True:
        line = lines.get(timeout=max(deadline - time.monotonic(), 0.1))

        if line.startswith("PAUSED"):
            return line.strip()


def wait_lock_released(pg: psycopg.Connection) -> None:
    """죽은 worker의 advisory lock이 풀릴 때까지"""

    deadline = time.monotonic() + 30

    while (
        pg
        .execute("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")
        .fetchone()
    )[0]:
        assert time.monotonic() < deadline
        time.sleep(0.05)


def document_sends(
    pg: psycopg.Connection,
    path: str,
) -> list[str]:
    return [
        row[0]
        for row in (
            pg
            .execute(
                "SELECT state FROM collector.http_sends WHERE document_id = %s ORDER BY send_id",
                (doc_id(path),),
            )
            .fetchall()
        )
    ]


def batch_epochs(
    pg: psycopg.Connection,
    job_id: UUID,
) -> list[int | None]:
    return [
        row[0]
        for row in (
            pg
            .execute(
                """
                SELECT b.sealed_epoch
                FROM collector.collection_batches b JOIN collector.collection_runs r USING (run_id)
                WHERE r.job_id = %s ORDER BY b.ordinal
                """,
                (job_id,),
            )
            .fetchall()
        )
    ]


def ready_state(
    pg: psycopg.Connection,
    job_id: UUID,
) -> tuple[int | None, str | None]:
    return (
        pg
        .execute(
            "SELECT readiness_sequence, ready_run_id::text FROM collector.collection_runs WHERE job_id = %s",
            (job_id,),
        )
        .fetchone()
    )


@pytest.mark.parametrize(
    "point",
    [
        "http-in-flight",
        "reserved-before-connect",
        "raw-stored-before-record",
        "sealed-before-envelope",
        "envelope-stored-before-record",
        "ready-run-stored-before-success",
    ],
)
def test_kill_and_resume(
    point: str,
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
    store: ObjectStore,
) -> None:
    job_id = submit(settings, tmp_path)
    pages = site(pages=PAGES)
    order = fetch_order(pages)
    target = order[40]
    pause = {
        "http-in-flight": [],
        "reserved-before-connect": ["--pause-url", url_of(target)],
        "raw-stored-before-record": ["--pause-key", raw_key(
            "spring-boot",
            "4.1.3",
            pages[target],
        ), "--pause-after"],
        "sealed-before-envelope": ["--pause-key", "/envelope.json"],
        "envelope-stored-before-record": ["--pause-key", "/envelope.json", "--pause-after"],
        "ready-run-stored-before-success": ["--pause-key", "/ready-run.json", "--pause-after"],
    }[point]

    with serving(pages) as server:
        if point == "http-in-flight":
            # robots, entry 다음 41번째 문서 요청
            server.hold(2 + 41)

        process = start(
            settings,
            server,
            *pause,
            "work",
        )

        if point == "http-in-flight":
            assert server.held.wait(120)
            assert server.held_path == target
        else:
            paused(process)

        process.kill()
        process.wait()
        server.release()
        before = ready_state(pg, job_id)
        (run_id,) = (
            pg
            .execute("SELECT run_id FROM collector.collection_runs WHERE job_id = %s", (job_id,))
            .fetchone()
        )
        uploaded = store.get(f"manifests/{run_id}/ready-run.json")
        wait_lock_released(pg)
        code, _, err = work(
            settings,
            server,
            "work",
        )

    assert code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["attempts"], state["epoch"]) == ("SUCCEEDED", 2, 2)
    summary = ready_run(store, state)
    assert summary["raw"] == expected(pages) and summary["batches"] == 2
    counts = server.counts()
    gap = sends(pg) - server.total()
    assert 0 <= gap <= 1
    entry = ROOT_PATH + "index.html"
    # entry는 발견과 문서로 두 번
    assert all(counts[path] == 1 for path in order[:40] if path != entry)
    assert counts["/robots.txt"] == 1 and counts[entry] == 2

    if point == "http-in-flight":
        assert counts[target] == 2 and gap == 0
        assert document_sends(pg, target) == ["UNKNOWN", "SENT"]
    elif point == "reserved-before-connect":
        assert counts[target] == 1 and gap == 1
        assert document_sends(pg, target) == ["UNKNOWN", "SENT"]
    elif point == "raw-stored-before-record":
        assert counts[target] == 2 and gap == 0
        assert document_sends(pg, target) == ["SENT", "SENT"]
    else:
        assert all(count == 1 for path, count in counts.items() if path != entry)
        assert gap == 0

    if point in ("http-in-flight", "reserved-before-connect", "raw-stored-before-record"):
        assert targets(pg, job_id)[doc_id(target)] == ("SUCCEEDED", 2, None)

    if point in ("sealed-before-envelope", "envelope-stored-before-record"):
        assert batch_epochs(pg, job_id)[0] == 1
        assert summary["lease_epochs"][0] == 1

    if point == "ready-run-stored-before-success":
        assert ready_state(pg, job_id) == before
        assert uploaded is not None and content_hash(uploaded) == state["hash"]


def test_second_worker_exits_while_the_first_holds_the_lock(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    job_id = submit(settings, tmp_path)

    with serving(site(pages=PAGES)) as server:
        first = start(
            settings,
            server,
            "work",
        )
        deadline = time.monotonic() + 30

        while (
            pg
            .execute("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted")
            .fetchone()
        ) != (1,):
            assert time.monotonic() < deadline
            time.sleep(0.05)

        second_code, _, _ = work(
            settings,
            server,
            "work",
        )
        first_code, _, err = finish(first)

    assert second_code == 5
    assert first_code == 0, err
    state = job(pg, job_id)
    assert (state["state"], state["attempts"]) == ("SUCCEEDED", 1)
    assert server.total() == 152


def test_concurrent_submit_processes_share_one_job(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    with connected_app(settings, LoopbackConnector(9)) as app:
        approve_sources(
            app,
            settings,
            tmp_path,
            definition(),
        )
        request = tmp_path / "request.json"
        request.write_text(
            json.dumps(
                app.registry
                .draft_request([SOURCE_ID])
                .to_json(),
            ),
            encoding="utf-8",
        )

    processes = [
        subprocess.Popen(
            run_collector_command(
                "--port",
                9,
                "submit",
                request,
            ),
            cwd=ROOT,
            env=subprocess_env(settings),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        for _ in range(2)
    ]
    accepts = []

    for process in processes:
        code, out, err = finish(process)
        assert code == 0, err
        accepts.append(json.loads(out))

    assert len({accept["jobId"] for accept in accepts}) == 1
    assert sorted(accept["duplicate"] for accept in accepts) == [False, True]
    assert (
        pg
        .execute("SELECT count(*) FROM collector.jobs")
        .fetchone()
    ) == (1,)


def test_two_collectors_without_the_lock_still_send_one_at_a_time(
    settings: Settings,
    tmp_path: Path,
    pg: psycopg.Connection,
) -> None:
    """가짜 lock으로 두 수집 프로세스가 각자 job을 claim해도 외부 동시 요청은 1"""

    pages = 40

    with connected_app(settings, LoopbackConnector(9)) as app:
        approve_sources(
            app,
            settings,
            tmp_path,
            definition(),
        )
        job_ids = [app.jobs.submit(app.registry.draft_request([SOURCE_ID])).job_id for _ in range(2)]

    with serving(site(pages=pages), delay=0.05) as server:
        processes = [start(
            settings,
            server,
            "--no-lock",
            "work",
        ) for _ in range(2)]
        results = [finish(process) for process in processes]

    assert [result[0] for result in results] == [0, 0], results
    assert {job(pg, job_id)["state"] for job_id in job_ids} == {"SUCCEEDED"}
    assert {job(pg, job_id)["attempts"] for job_id in job_ids} == {1}
    assert server.max_in_flight == 1
    assert min(server.gaps()) >= SCALED.min_interval * 0.9
    assert server.total() == sends(pg) == 1 + 2 * (1 + pages)
