"""V001, V002 trigger와 CHECK가 강제하는 불변 규칙"""

from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg.errors import CheckViolation, RestrictViolation, UniqueViolation
from psycopg.types.json import Jsonb

from collector.crawl.transport_gate import RESERVE_EXPIRE
from collector.job.job_repository import RECOVER_SENDS, RECOVER_TARGETS
from tests.conftest import definition, insert_job, insert_run, insert_source

RUNNING = """
UPDATE collector.jobs
SET state = 'RUNNING', lease_owner = %(owner)s, lease_epoch = lease_epoch + 1,
    lease_until = clock_timestamp() + interval '1 minute', attempt_count = attempt_count + 1
WHERE job_id = %(job_id)s
"""


def run_job(
    pg: psycopg.Connection,
    job_id: UUID,
) -> None:
    pg.execute(RUNNING, {"owner": uuid4(), "job_id": job_id})


def set_job(
    pg: psycopg.Connection,
    job_id: UUID,
    assignment: str,
) -> None:
    pg.execute(f"UPDATE collector.jobs SET {assignment} WHERE job_id = %s", (job_id,))


def insert_send(
    pg: psycopg.Connection,
    state: str = "SENDING",
    reserved_at: str | None = None,
    budget_limit: int = 3000,
) -> int:
    finished = "NULL" if state == "SENDING" else "clock_timestamp()"
    status = "200" if state == "SENT" else "NULL"
    reserved = "clock_timestamp()" if reserved_at is None else "%(reserved_at)s"
    (send_id,) = (
        pg
        .execute(
            f"""
            INSERT INTO collector.http_sends
                (reserved_at, budget_limit, host, probe_source, purpose, path, state, http_status, finished_at)
            VALUES ({reserved}, %(limit)s, 'docs.spring.io', 'spring-boot-4.1.3-reference', 'ROBOTS',
                    '/robots.txt', %(state)s, {status}, {finished})
            RETURNING send_id
            """,
            {"reserved_at": reserved_at, "limit": budget_limit, "state": state},
        )
        .fetchone()
    )
    return send_id


def test_flyway_history_has_v001_and_v002(pg: psycopg.Connection) -> None:
    rows = (
        pg
        .execute(
            "SELECT version, success FROM collector.flyway_schema_history WHERE version IS NOT NULL",
        )
        .fetchall()
    )

    assert rows == [("001", True), ("002", True)]


def raw_key_checks(pg: psycopg.Connection) -> list[tuple[str, str]]:
    return (
        pg
        .execute(
            """
            SELECT conname, pg_get_constraintdef(oid)
            FROM pg_constraint
            WHERE conrelid = 'collector.collection_targets'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%raw_key%'
            ORDER BY conname
            """,
        )
        .fetchall()
    )


def test_raw_key_check_takes_html_and_txt_named_by_the_raw_hash(pg: psycopg.Connection) -> None:
    checks = raw_key_checks(pg)

    assert [name for name, body in checks if "raw_key ~" in body or "substr(raw_key" in body] == [
        "collection_targets_raw_key_shape",
    ]
    assert any("raw_key IS NOT NULL" in body for _, body in checks)

    insert_source(pg)
    run_id, _, documents = insert_run(
        pg,
        insert_job(pg),
        documents=6,
    )

    def succeed(
        document_id: str,
        key_hash: str,
        raw_hash: str,
        extension: str,
    ) -> None:
        pg.execute(
            """
            UPDATE collector.collection_targets
            SET state = 'SUCCEEDED', raw_key = %(key)s, raw_hash = %(hash)s, raw_size = 1,
                observed_at = clock_timestamp(), final_url = 'https://docs.spring.io/x.html'
            WHERE run_id = %(run_id)s AND document_id = %(document_id)s
            """,
            {
                "key": f"raw/spring-boot/4.1.2/{key_hash}.{extension}",
                "hash": "sha256:" + raw_hash,
                "run_id": run_id,
                "document_id": document_id,
            },
        )

    succeed(
        documents[0],
        "1" * 64,
        "1" * 64,
        "html",
    )
    succeed(
        documents[1],
        "2" * 64,
        "2" * 64,
        "txt",
    )

    for document_id, key_hash, extension in (
        (documents[2], "4" * 64, "txt"),
        (documents[3], "3" * 64, "kt"),
        (documents[4], "3" * 64, "txt.html"),
    ):
        with pytest.raises(CheckViolation):
            succeed(
                document_id,
                key_hash,
                "3" * 64,
                extension,
            )

    with pytest.raises(CheckViolation):
        pg.execute(
            "UPDATE collector.collection_targets SET raw_key = %s WHERE run_id = %s AND document_id = %s",
            ("raw/spring-boot/4.1.2/" + "5" * 64 + ".txt", run_id, documents[5]),
        )


def test_jobs_guard(pg: psycopg.Connection) -> None:
    insert_source(pg)

    with pytest.raises(CheckViolation):
        pg.execute(
            """
            INSERT INTO collector.jobs (job_id, request_id, request_hash, request_body, state,
                                        lease_owner, lease_until)
            VALUES (%s, %s, %s, '{}', 'RUNNING', %s, clock_timestamp())
            """,
            (uuid4(), uuid4(), "sha256:" + "b" * 64, uuid4()),
        )

    job_id = insert_job(pg)

    run_job(pg, job_id)

    with pytest.raises(RestrictViolation):
        set_job(
            pg,
            job_id,
            "state = 'QUEUED'",
        )

    with pytest.raises(RestrictViolation):
        set_job(
            pg,
            job_id,
            "request_hash = 'sha256:" + "d" * 64 + "'",
        )

    set_job(
        pg,
        job_id,
        "attempt_count = 2, lease_epoch = 2",
    )

    with pytest.raises(RestrictViolation):
        set_job(
            pg,
            job_id,
            "attempt_count = 1",
        )

    with pytest.raises(RestrictViolation):
        set_job(
            pg,
            job_id,
            "lease_epoch = 1",
        )

    set_job(
        pg,
        job_id,
        "cancel_requested = true",
    )

    with pytest.raises(RestrictViolation):
        set_job(
            pg,
            job_id,
            "cancel_requested = false",
        )

    failed = insert_job(pg)
    run_job(pg, failed)
    set_job(
        pg,
        failed,
        "state = 'FAILED_FINAL', last_error_code = 'INTERNAL_ERROR', lease_until = NULL",
    )

    with pytest.raises(RestrictViolation):
        run_job(pg, failed)

    succeeded = insert_job(pg)
    run_job(pg, succeeded)
    set_job(
        pg,
        succeeded,
        "state = 'SUCCEEDED', result_key = 'manifests/r/ready-run.json', "
        f"result_hash = 'sha256:{'e' * 64}', result_size = 1, lease_until = NULL",
    )

    with pytest.raises(RestrictViolation):
        set_job(
            pg,
            succeeded,
            "updated_at = clock_timestamp()",
        )

    with pytest.raises(RestrictViolation):
        pg.execute("DELETE FROM collector.jobs WHERE job_id = %s", (job_id,))


def test_targets_guard(pg: psycopg.Connection) -> None:
    insert_source(pg)
    job_id = insert_job(pg)
    run_id, batch_id, (first, second) = insert_run(pg, job_id)
    where = {"run_id": run_id, "document_id": first}

    def update(assignment: str, document_id: str = first) -> None:
        pg.execute(
            f"UPDATE collector.collection_targets SET {assignment} "
            "WHERE run_id = %(run_id)s AND document_id = %(document_id)s",
            {**where, "document_id": document_id},
        )

    with pytest.raises(CheckViolation):
        update("attempt_count = 4")

    with pytest.raises(RestrictViolation):
        update("attempt_cap = 2")

    with pytest.raises(CheckViolation):
        pg.execute(
            """
            INSERT INTO collector.collection_targets (run_id, document_id, batch_id, source_id, url, attempt_cap)
            VALUES (%s, %s, %s, 'spring-boot-4.1.3-reference', 'https://docs.spring.io/x.html', 4)
            """,
            (run_id, "sha256:" + "f" * 64, batch_id),
        )

    update("attempt_count = 2")

    with pytest.raises(RestrictViolation):
        update("attempt_count = 1")

    with pytest.raises(CheckViolation):
        update(
            "state = 'SUCCEEDED', raw_key = 'raw/spring-boot/4.1.3/" + "1" * 64 + ".html', "
            f"raw_hash = 'sha256:{'2' * 64}', raw_size = 1, observed_at = clock_timestamp(), "
            "final_url = 'https://docs.spring.io/x.html'",
        )

    update(
        "state = 'SUCCEEDED', raw_key = 'raw/spring-boot/4.1.3/" + "1" * 64 + ".html', "
        f"raw_hash = 'sha256:{'1' * 64}', raw_size = 1, observed_at = clock_timestamp(), "
        "final_url = 'https://docs.spring.io/x.html'",
    )

    with pytest.raises(RestrictViolation):
        update("etag = 'x'")

    update(
        "state = 'FAILED', error_code = 'HTTP_RETRY_EXHAUSTED', needs_review = true, failed_at = clock_timestamp()",
        second,
    )
    pg.execute(
        """
        UPDATE collector.collection_batches
        SET sealed_at = clock_timestamp(), sealed_epoch = 1, success_count = 1, failure_count = 1
        WHERE batch_id = %s
        """,
        (batch_id,),
    )

    with pytest.raises(RestrictViolation):
        pg.execute(
            "UPDATE collector.collection_batches SET sealed_epoch = 2 WHERE batch_id = %s",
            (batch_id,),
        )

    with pytest.raises(RestrictViolation):
        pg.execute("DELETE FROM collector.collection_targets WHERE run_id = %s", (run_id,))


def test_sealed_batch_targets_are_fixed(pg: psycopg.Connection) -> None:
    insert_source(pg)
    run_id, batch_id, (first,) = insert_run(
        pg,
        insert_job(pg),
        documents=1,
    )
    pg.execute(
        "UPDATE collector.collection_targets SET state = 'FAILED', error_code = 'URL_DENIED', "
        "needs_review = true, failed_at = clock_timestamp() WHERE run_id = %s",
        (run_id,),
    )
    pg.execute(
        "UPDATE collector.collection_batches SET sealed_at = clock_timestamp(), sealed_epoch = 1, "
        "success_count = 0, failure_count = 1 WHERE batch_id = %s",
        (batch_id,),
    )

    with pytest.raises(RestrictViolation):
        pg.execute(
            "UPDATE collector.collection_targets SET next_attempt_at = clock_timestamp() WHERE run_id = %s",
            (run_id,),
        )


def test_runs_move_forward_and_set_once(pg: psycopg.Connection) -> None:
    insert_source(pg)
    run_id, _, _ = insert_run(pg, insert_job(pg))

    def update(assignment: str) -> None:
        pg.execute(f"UPDATE collector.collection_runs SET {assignment} WHERE run_id = %s", (run_id,))

    update("discovery_state = 'CLOSED', sealed_at = clock_timestamp(), official_revision = 'v4.1.3'")

    with pytest.raises(RestrictViolation):
        update("official_revision = 'v4.1.4'")

    with pytest.raises(RestrictViolation):
        update("discovery_state = 'DISCOVERING', sealed_at = NULL")

    update("drift_detected = true")

    with pytest.raises(RestrictViolation):
        update("drift_detected = false")

    with pytest.raises(RestrictViolation):
        pg.execute("DELETE FROM collector.collection_runs WHERE run_id = %s", (run_id,))


def test_budget_day_is_kst_date(pg: psycopg.Connection) -> None:
    insert_source(pg, approved=False)
    before = insert_send(
        pg,
        "SENT",
        "2026-11-23T14:59:59Z",
    )
    after = insert_send(
        pg,
        "SENT",
        "2026-11-23T15:00:00Z",
    )
    days = dict(
        (
            pg
            .execute(
                "SELECT send_id, budget_day::text FROM collector.http_sends WHERE send_id IN (%s, %s)",
                (before, after),
            )
            .fetchall()
        ),
    )

    assert days == {before: "2026-11-23", after: "2026-11-24"}


def test_sends_finish_once(pg: psycopg.Connection) -> None:
    insert_source(pg, approved=False)
    sent = insert_send(pg)

    with pytest.raises(UniqueViolation):
        insert_send(pg)

    pg.execute(
        "UPDATE collector.http_sends SET state = 'SENT', http_status = 200, finished_at = clock_timestamp() "
        "WHERE send_id = %s",
        (sent,),
    )

    with pytest.raises(RestrictViolation):
        pg.execute("UPDATE collector.http_sends SET http_status = 404 WHERE send_id = %s", (sent,))

    with pytest.raises(CheckViolation):
        pg.execute(
            "INSERT INTO collector.http_sends (budget_limit, host, probe_source, purpose, path, state, finished_at) "
            "VALUES (3000, 'docs.spring.io', 'spring-boot-4.1.3-reference', 'ROBOTS', '/', 'SENT', clock_timestamp())",
        )

    unknown = insert_send(pg)
    pg.execute(
        "UPDATE collector.http_sends SET state = 'UNKNOWN', finished_at = clock_timestamp() WHERE send_id = %s",
        (unknown,),
    )
    insert_send(pg)

    with pytest.raises(RestrictViolation):
        pg.execute("DELETE FROM collector.http_sends WHERE send_id = %s", (sent,))


def test_recover_and_expiry_change_sending_rows(pg: psycopg.Connection) -> None:
    insert_source(pg)
    job_id = insert_job(pg)
    run_job(pg, job_id)
    run_id, _, (first, _) = insert_run(pg, job_id)
    pg.execute(
        "UPDATE collector.collection_targets SET state = 'SENDING', attempt_count = 1, sending_epoch = 1 "
        "WHERE run_id = %s AND document_id = %s",
        (run_id, first),
    )
    pg.execute(
        """
        INSERT INTO collector.http_sends (budget_limit, host, job_id, lease_epoch, purpose, document_id, path)
        VALUES (3000, 'docs.spring.io', %s, 1, 'DOCUMENT', %s, '/p000.html')
        """,
        (job_id, first),
    )
    params = {"job_id": job_id, "epoch": 2, "run_id": run_id, "recover_delay_seconds": 0.0}

    assert pg.execute(RECOVER_SENDS, params).rowcount == 1
    assert pg.execute(RECOVER_TARGETS, params).rowcount == 1

    expired = insert_send(pg, reserved_at="2026-01-01T00:00:00Z")

    assert pg.execute(RESERVE_EXPIRE, {"in_flight_expiry_seconds": 150.0}).rowcount == 1
    assert (
        pg
        .execute(
            "SELECT state FROM collector.http_sends WHERE send_id = %s",
            (expired,),
        )
        .fetchone()
    ) == ("UNKNOWN",)


def test_budget_trigger_uses_row_limit(pg: psycopg.Connection) -> None:
    insert_source(pg, approved=False)

    for _ in range(3):
        insert_send(
            pg,
            "SENT",
            budget_limit=3,
        )

    with pytest.raises(CheckViolation):
        insert_send(
            pg,
            "SENT",
            budget_limit=3,
        )

    insert_send(
        pg,
        "SENT",
        budget_limit=5,
    )


def test_sources_guard(pg: psycopg.Connection) -> None:
    entry = definition()
    pg.execute(
        "INSERT INTO collector.sources (source_id, definition, definition_hash) VALUES (%s, %s, %s)",
        (entry["sourceId"], Jsonb(entry), "sha256:" + "a" * 64),
    )
    approve = (
        "UPDATE collector.sources SET approval_state = 'APPROVED', approved_by = 'tester', "
        "approved_at = clock_timestamp(), parser_profile_ref = 'spring-antora-reference@2' WHERE source_id = %s"
    )

    with pytest.raises(CheckViolation):
        pg.execute(approve, (entry["sourceId"],))

    pg.execute(
        "UPDATE collector.sources SET probe_state = 'PASSED', probe_version = '4.1.4', "
        "probe_raw_hash = %s, probed_at = clock_timestamp() WHERE source_id = %s",
        ("sha256:" + "a" * 64, entry["sourceId"]),
    )

    with pytest.raises(CheckViolation):
        pg.execute(approve, (entry["sourceId"],))

    pg.execute("UPDATE collector.sources SET probe_version = '4.1.3' WHERE source_id = %s", (entry["sourceId"],))
    pg.execute(approve, (entry["sourceId"],))

    with pytest.raises(RestrictViolation):
        pg.execute(
            "UPDATE collector.sources SET definition_hash = %s WHERE source_id = %s",
            ("sha256:" + "b" * 64, entry["sourceId"]),
        )

    pg.execute(
        "UPDATE collector.sources SET approval_state = 'REVOKED', revoked_by = 'tester', "
        "revoked_at = clock_timestamp() WHERE source_id = %s",
        (entry["sourceId"],),
    )

    with pytest.raises(RestrictViolation):
        pg.execute("UPDATE collector.sources SET revoked_by = 'other' WHERE source_id = %s", (entry["sourceId"],))

    with pytest.raises(RestrictViolation):
        pg.execute("DELETE FROM collector.sources WHERE source_id = %s", (entry["sourceId"],))
