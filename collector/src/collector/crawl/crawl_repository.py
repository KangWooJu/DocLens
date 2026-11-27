"""수집 회차 SQL: run, projection, batch, 문서 대상과 결과, 봉인과 readyRun"""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Literal
from uuid import UUID

from psycopg.types.json import Jsonb

from collector.contract.canonical_json import JsonValue
from collector.db import Conn
from collector.store.object_store import ObjectRef

RUN_COLUMNS = """run_id, job_id, snapshot_id, scope_hash, source_registry_hash, observation_sequence, started_at,
       discovery_state, official_revision, sealed_at, targets_key, targets_hash, targets_size,
       discovery_key, discovery_hash, discovery_size, drift_detected, ready_run_id, readiness_sequence,
       ready_created_at, ready_key, ready_hash, ready_size"""

BATCH_COLUMNS = """batch_id, ordinal, project, exact_version, document_type, target_count,
       targets_key, targets_hash, targets_size, sealed_at, sealed_epoch, success_count, failure_count,
       outcomes_key, outcomes_hash, outcomes_size, envelope_key, envelope_hash, envelope_size"""

TARGET_COLUMNS = """document_id, batch_id, source_id, url, state, attempt_count, raw_key, raw_hash, raw_size,
       http_status, final_url, content_type, last_modified, etag, observed_version, observed_at,
       error_code, needs_review, failed_at"""

# job 하나에 run 하나, 재개하면 기존 행
INSERT_RUN = """
INSERT INTO collector.collection_runs
    (run_id, job_id, snapshot_id, scope_hash, source_registry_hash, observation_sequence)
VALUES (%(run_id)s, %(job_id)s, %(snapshot_id)s, %(scope_hash)s, %(source_registry_hash)s,
        nextval('collector.observation_sequence'))
ON CONFLICT (job_id) DO NOTHING
RETURNING observation_sequence
"""

SELECT_RUN = f"""
SELECT {RUN_COLUMNS}
FROM collector.collection_runs
WHERE job_id = %(job_id)s
"""

INSERT_PROJECTION = """
INSERT INTO collector.collection_projections
    (run_id, project, exact_version, applicability, input_scope_key, observation_sequence)
VALUES (%(run_id)s, %(project)s, %(exact_version)s, %(applicability)s, %(input_scope_key)s,
        %(observation_sequence)s)
"""

SELECT_PROJECTIONS = """
SELECT project, exact_version, applicability, input_scope_key, observation_sequence
FROM collector.collection_projections
WHERE run_id = %(run_id)s
ORDER BY project, exact_version
"""

INSERT_BATCH = """
INSERT INTO collector.collection_batches
    (batch_id, run_id, ordinal, project, exact_version, document_type, target_count)
VALUES (%(batch_id)s, %(run_id)s, %(ordinal)s, %(project)s, %(exact_version)s, %(document_type)s,
        %(target_count)s)
"""

INSERT_TARGET = """
INSERT INTO collector.collection_targets (run_id, document_id, batch_id, source_id, url, attempt_cap)
VALUES (%(run_id)s, %(document_id)s, %(batch_id)s, %(source_id)s, %(url)s, %(attempt_cap)s)
"""

# 발견 닫기: 대상 목록과 봉인 시각, 공식 revision 고정
CLOSE_RUN = """
UPDATE collector.collection_runs
SET discovery_state = 'CLOSED', sealed_at = date_trunc('second', clock_timestamp()),
    official_revision = %(official_revision)s
WHERE run_id = %(run_id)s AND discovery_state = 'DISCOVERING'
"""

SELECT_BATCHES = f"""
SELECT {BATCH_COLUMNS}
FROM collector.collection_batches
WHERE run_id = %(run_id)s
ORDER BY ordinal
"""

# batch 순서, 그 안에서 canonicalUrl 순
SELECT_TARGETS = f"""
SELECT {TARGET_COLUMNS}
FROM collector.collection_targets t
JOIN collector.collection_batches b USING (batch_id)
WHERE t.run_id = %(run_id)s
ORDER BY b.ordinal, t.url
"""

RECORD_RUN_TARGETS = """
UPDATE collector.collection_runs
SET targets_key = %(key)s, targets_hash = %(hash)s, targets_size = %(size)s
WHERE run_id = %(owner_id)s AND targets_key IS NULL
"""

# discovery 기록이 곧 SEALED 전이
RECORD_DISCOVERY = """
UPDATE collector.collection_runs
SET discovery_key = %(key)s, discovery_hash = %(hash)s, discovery_size = %(size)s, discovery_state = 'SEALED'
WHERE run_id = %(owner_id)s AND discovery_key IS NULL AND targets_key IS NOT NULL
"""

RECORD_BATCH_TARGETS = """
UPDATE collector.collection_batches
SET targets_key = %(key)s, targets_hash = %(hash)s, targets_size = %(size)s
WHERE batch_id = %(owner_id)s AND targets_key IS NULL
"""

RECORD_OUTCOMES = """
UPDATE collector.collection_batches
SET outcomes_key = %(key)s, outcomes_hash = %(hash)s, outcomes_size = %(size)s
WHERE batch_id = %(owner_id)s AND outcomes_key IS NULL
"""

RECORD_ENVELOPE = """
UPDATE collector.collection_batches
SET envelope_key = %(key)s, envelope_hash = %(hash)s, envelope_size = %(size)s
WHERE batch_id = %(owner_id)s AND envelope_key IS NULL
"""

# 기한이 된 PENDING 문서, documentId 순
SELECT_DUE_TARGET = """
SELECT document_id, url, source_id
FROM collector.collection_targets
WHERE run_id = %(run_id)s AND state = 'PENDING'
  AND coalesce(next_attempt_at, '-infinity') <= clock_timestamp()
ORDER BY document_id
LIMIT 1
"""

# 끝나지 않은 문서 수와 가장 이른 다음 시도까지 PG가 계산한 남은 초
SELECT_OPEN_TARGETS = """
SELECT count(*),
       extract(epoch FROM min(next_attempt_at) FILTER (WHERE state = 'PENDING') - clock_timestamp())
FROM collector.collection_targets
WHERE run_id = %(run_id)s AND state IN ('PENDING', 'SENDING')
"""

SELECT_ATTEMPT = """
SELECT attempt_count
FROM collector.collection_targets
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
"""

TARGET_SUCCEEDED = """
UPDATE collector.collection_targets
SET state = 'SUCCEEDED', sending_epoch = NULL,
    raw_key = %(raw_key)s, raw_hash = %(raw_hash)s, raw_size = %(raw_size)s,
    http_status = %(status)s, final_url = %(final_url)s, content_type = %(content_type)s,
    last_modified = %(last_modified)s, etag = %(etag)s,
    observed_version = %(version)s, versioned_url = %(versioned_url)s, source_tag = %(source_tag)s,
    observed_at = %(observed_at)s
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND state = 'SENDING' AND sending_epoch = %(epoch)s
"""

# 다음 시도 시각은 PG 시계
TARGET_RETRY = """
UPDATE collector.collection_targets
SET state = 'PENDING', sending_epoch = NULL,
    next_attempt_at = clock_timestamp() + make_interval(secs => %(delay_seconds)s)
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND state = 'SENDING' AND sending_epoch = %(epoch)s
"""

# 보낸 뒤 실패(SENDING) 또는 예약 전에 거부된 문서(PENDING)
TARGET_FAILED = """
UPDATE collector.collection_targets
SET state = 'FAILED', sending_epoch = NULL,
    error_code = %(error_code)s, needs_review = %(needs_review)s,
    failed_at = date_trunc('second', clock_timestamp()),
    http_status = %(status)s, observed_at = %(observed_at)s
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND (state = 'PENDING' OR (state = 'SENDING' AND sending_epoch = %(epoch)s))
"""

# 닫힘 검증: 페이지 nav 중 봉인 목록에 없는 링크 수
COUNT_UNKNOWN_LINKS = """
SELECT count(*)
FROM unnest(%(urls)s::text[]) AS link(url)
WHERE NOT EXISTS (SELECT 1 FROM collector.collection_targets t
                  WHERE t.run_id = %(run_id)s AND t.url = link.url)
"""

# 닫힘 검증 위반
RUN_DRIFT = """
UPDATE collector.collection_runs SET drift_detected = true WHERE run_id = %(run_id)s AND NOT drift_detected
"""

# 봉인 전 승인 재확인, 송신이 없는 봉인 단계도 철회 뒤에는 멈춤
SEAL_APPROVAL_RECHECK = """
SELECT count(*) AS unapproved
FROM jsonb_array_elements(%(request_inputs)s::jsonb) AS i
WHERE NOT EXISTS (SELECT 1
                  FROM collector.sources s
                  WHERE s.source_id = i ->> 'sourceId' AND s.approval_state = 'APPROVED'
                  FOR SHARE)
"""

SEAL_BATCH = """
UPDATE collector.collection_batches b
SET sealed_at = date_trunc('second', clock_timestamp()),
    sealed_epoch = %(epoch)s,
    success_count = (SELECT count(*) FROM collector.collection_targets t
                     WHERE t.batch_id = b.batch_id AND t.state = 'SUCCEEDED'),
    failure_count = (SELECT count(*) FROM collector.collection_targets t
                     WHERE t.batch_id = b.batch_id AND t.state = 'FAILED')
WHERE b.batch_id = %(batch_id)s AND b.sealed_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM collector.collection_targets t
                  WHERE t.batch_id = b.batch_id AND t.state IN ('PENDING', 'SENDING'))
"""

READY_RUN_FIX = """
UPDATE collector.collection_runs
SET ready_run_id = %(new_uuid)s,
    readiness_sequence = nextval('collector.readiness_sequence'),
    ready_created_at = date_trunc('second', clock_timestamp())
WHERE run_id = %(run_id)s AND ready_run_id IS NULL AND discovery_state = 'SEALED' AND NOT drift_detected
  AND NOT EXISTS (SELECT 1 FROM collector.collection_batches
                  WHERE run_id = %(run_id)s AND envelope_key IS NULL)
"""

READY_RUN_RECORD = """
UPDATE collector.collection_runs
SET ready_key = %(key)s, ready_hash = %(hash)s, ready_size = %(size)s
WHERE run_id = %(owner_id)s AND ready_key IS NULL
"""


type ObjectKind = Literal["RUN_TARGETS", "BATCH_TARGETS", "DISCOVERY", "OUTCOMES", "ENVELOPE", "READY_RUN"]

# 객체 종류별 key 기록 문장, owner는 run 또는 batch
RECORD_STATEMENTS = {
    "RUN_TARGETS": RECORD_RUN_TARGETS,
    "BATCH_TARGETS": RECORD_BATCH_TARGETS,
    "DISCOVERY": RECORD_DISCOVERY,
    "OUTCOMES": RECORD_OUTCOMES,
    "ENVELOPE": RECORD_ENVELOPE,
    "READY_RUN": READY_RUN_RECORD,
}


@dataclass(frozen=True)
class RunRow:
    run_id: UUID
    job_id: UUID
    snapshot_id: UUID
    scope_hash: str
    source_registry_hash: str
    observation_sequence: int
    started_at: datetime
    discovery_state: str
    official_revision: str | None
    sealed_at: datetime | None
    targets: ObjectRef | None
    discovery: ObjectRef | None
    drift_detected: bool
    ready_run_id: UUID | None
    readiness_sequence: int | None
    ready_created_at: datetime | None
    ready: ObjectRef | None


@dataclass(frozen=True)
class ProjectionRow:
    project: str
    exact_version: str
    applicability: str
    input_scope_key: str
    observation_sequence: int


@dataclass(frozen=True)
class BatchRow:
    batch_id: UUID
    ordinal: int
    project: str
    exact_version: str
    document_type: str
    target_count: int
    targets: ObjectRef | None
    sealed_at: datetime | None
    sealed_epoch: int | None
    success_count: int | None
    failure_count: int | None
    outcomes: ObjectRef | None
    envelope: ObjectRef | None


@dataclass(frozen=True)
class TargetRow:
    document_id: str
    batch_id: UUID
    source_id: str
    url: str
    state: str
    attempt_count: int
    raw: ObjectRef | None
    http_status: int | None
    final_url: str | None
    content_type: str | None
    last_modified: str | None
    etag: str | None
    observed_version: str | None
    observed_at: datetime | None
    error_code: str | None
    needs_review: bool | None
    failed_at: datetime | None


@dataclass(frozen=True)
class NewTarget:
    document_id: str
    source_id: str
    url: str


@dataclass(frozen=True)
class NewBatch:
    batch_id: UUID
    ordinal: int
    project: str
    exact_version: str
    document_type: str
    targets: tuple[NewTarget, ...]


@dataclass(frozen=True)
class DocumentResult:
    """SUCCEEDED 기록 값"""

    raw: ObjectRef
    status: int
    final_url: str
    content_type: str
    last_modified: str | None
    etag: str | None
    version: str | None
    versioned_url: str | None
    source_tag: str | None
    observed_at: datetime


class CrawlRepository:
    def insert_run(
        self,
        conn: Conn,
        run_id: UUID,
        job_id: UUID,
        scope_hash: str,
        source_registry_hash: str,
        snapshot_id: UUID,
    ) -> int | None:
        """새로 넣었으면 observationSequence, 이미 있으면 None"""

        row = (
            conn
            .execute(
                INSERT_RUN,
                {
                    "run_id": run_id,
                    "job_id": job_id,
                    "snapshot_id": snapshot_id,
                    "scope_hash": scope_hash,
                    "source_registry_hash": source_registry_hash,
                },
            )
            .fetchone()
        )
        return None if row is None else row[0]

    def insert_projection(
        self,
        conn: Conn,
        run_id: UUID,
        projection: ProjectionRow,
    ) -> None:
        conn.execute(
            INSERT_PROJECTION,
            {
                "run_id": run_id,
                "project": projection.project,
                "exact_version": projection.exact_version,
                "applicability": projection.applicability,
                "input_scope_key": projection.input_scope_key,
                "observation_sequence": projection.observation_sequence,
            },
        )

    def run(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> RunRow | None:
        row = (
            conn
            .execute(
                SELECT_RUN,
                {"job_id": job_id},
            )
            .fetchone()
        )

        if row is None:
            return None

        return RunRow(
            *row[:10],
            targets=_ref(*row[10:13]),
            discovery=_ref(*row[13:16]),
            drift_detected=row[16],
            ready_run_id=row[17],
            readiness_sequence=row[18],
            ready_created_at=row[19],
            ready=_ref(*row[20:23]),
        )

    def projections(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> tuple[ProjectionRow, ...]:
        rows = (
            conn
            .execute(
                SELECT_PROJECTIONS,
                {"run_id": run_id},
            )
            .fetchall()
        )
        return tuple(ProjectionRow(*row) for row in rows)

    def close(
        self,
        conn: Conn,
        run_id: UUID,
        batches: Sequence[NewBatch],
        attempt_cap: int,
        official_revision: str | None,
    ) -> None:
        """batch와 대상 삽입, 발견 상태 CLOSED"""

        for batch in batches:
            conn.execute(
                INSERT_BATCH,
                {
                    "batch_id": batch.batch_id,
                    "run_id": run_id,
                    "ordinal": batch.ordinal,
                    "project": batch.project,
                    "exact_version": batch.exact_version,
                    "document_type": batch.document_type,
                    "target_count": len(batch.targets),
                },
            )

            for target in batch.targets:
                conn.execute(
                    INSERT_TARGET,
                    {
                        "run_id": run_id,
                        "document_id": target.document_id,
                        "batch_id": batch.batch_id,
                        "source_id": target.source_id,
                        "url": target.url,
                        "attempt_cap": attempt_cap,
                    },
                )

        conn.execute(
            CLOSE_RUN,
            {"run_id": run_id, "official_revision": official_revision},
        )

    def batches(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> tuple[BatchRow, ...]:
        rows = (
            conn
            .execute(
                SELECT_BATCHES,
                {"run_id": run_id},
            )
            .fetchall()
        )
        return tuple(
            BatchRow(
                *row[:6],
                targets=_ref(*row[6:9]),
                sealed_at=row[9],
                sealed_epoch=row[10],
                success_count=row[11],
                failure_count=row[12],
                outcomes=_ref(*row[13:16]),
                envelope=_ref(*row[16:19]),
            )
            for row in rows
        )

    def targets(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> tuple[TargetRow, ...]:
        rows = (
            conn
            .execute(
                SELECT_TARGETS,
                {"run_id": run_id},
            )
            .fetchall()
        )
        return tuple(
            TargetRow(
                *row[:6],
                raw=_ref(*row[6:9]),
                http_status=row[9],
                final_url=row[10],
                content_type=row[11],
                last_modified=row[12],
                etag=row[13],
                observed_version=row[14],
                observed_at=row[15],
                error_code=row[16],
                needs_review=row[17],
                failed_at=row[18],
            )
            for row in rows
        )

    def record_object(
        self,
        conn: Conn,
        kind: ObjectKind,
        owner_id: UUID,
        ref: ObjectRef,
    ) -> None:
        conn.execute(
            RECORD_STATEMENTS[kind],
            {
                "owner_id": owner_id,
                "key": ref.object_key,
                "hash": ref.content_hash,
                "size": ref.size_bytes,
            },
        )

    def due_target(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> tuple[str, str, str] | None:
        return (
            conn
            .execute(
                SELECT_DUE_TARGET,
                {"run_id": run_id},
            )
            .fetchone()
        )

    def open_targets(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> tuple[int, float | None]:
        """끝나지 않은 문서 수와 가장 이른 다음 시도까지 남은 초"""

        row = (
            conn
            .execute(
                SELECT_OPEN_TARGETS,
                {"run_id": run_id},
            )
            .fetchone()
        )
        assert row is not None
        return row[0], None if row[1] is None else float(row[1])

    def attempt(
        self,
        conn: Conn,
        run_id: UUID,
        document_id: str,
    ) -> int:
        row = (
            conn
            .execute(
                SELECT_ATTEMPT,
                {"run_id": run_id, "document_id": document_id},
            )
            .fetchone()
        )
        assert row is not None
        return row[0]

    def succeed_target(
        self,
        conn: Conn,
        run_id: UUID,
        document_id: str,
        epoch: int,
        result: DocumentResult,
    ) -> None:
        conn.execute(
            TARGET_SUCCEEDED,
            {
                "run_id": run_id,
                "document_id": document_id,
                "epoch": epoch,
                "raw_key": result.raw.object_key,
                "raw_hash": result.raw.content_hash,
                "raw_size": result.raw.size_bytes,
                "status": result.status,
                "final_url": result.final_url,
                "content_type": result.content_type,
                "last_modified": result.last_modified,
                "etag": result.etag,
                "version": result.version,
                "versioned_url": result.versioned_url,
                "source_tag": result.source_tag,
                "observed_at": result.observed_at,
            },
        )

    def retry_target(
        self,
        conn: Conn,
        run_id: UUID,
        document_id: str,
        epoch: int,
        delay_seconds: float,
    ) -> None:
        conn.execute(
            TARGET_RETRY,
            {
                "run_id": run_id,
                "document_id": document_id,
                "epoch": epoch,
                "delay_seconds": delay_seconds,
            },
        )

    def fail_target(
        self,
        conn: Conn,
        run_id: UUID,
        document_id: str,
        epoch: int,
        error_code: str,
        status: int | None,
        observed_at: datetime | None,
    ) -> None:
        conn.execute(
            TARGET_FAILED,
            {
                "run_id": run_id,
                "document_id": document_id,
                "epoch": epoch,
                "error_code": error_code,
                "needs_review": True,
                "status": status,
                "observed_at": observed_at,
            },
        )

    def unknown_links(
        self,
        conn: Conn,
        run_id: UUID,
        urls: Sequence[str],
    ) -> int:
        row = (
            conn
            .execute(
                COUNT_UNKNOWN_LINKS,
                {"run_id": run_id, "urls": list(urls)},
            )
            .fetchone()
        )
        assert row is not None
        return row[0]

    def mark_drift(
        self,
        conn: Conn,
        run_id: UUID,
    ) -> None:
        conn.execute(
            RUN_DRIFT,
            {"run_id": run_id},
        )

    def unapproved(
        self,
        conn: Conn,
        inputs: Sequence[JsonValue],
    ) -> int:
        row = (
            conn
            .execute(
                SEAL_APPROVAL_RECHECK,
                {"request_inputs": Jsonb(list(inputs))},
            )
            .fetchone()
        )
        assert row is not None
        return row[0]

    def seal_batch(
        self,
        conn: Conn,
        batch_id: UUID,
        epoch: int,
    ) -> bool:
        """모든 대상이 끝났으면 봉인 값을 고정하고 True"""

        return conn.execute(
            SEAL_BATCH,
            {"batch_id": batch_id, "epoch": epoch},
        ).rowcount == 1

    def fix_ready_run(
        self,
        conn: Conn,
        run_id: UUID,
        ready_run_id: UUID,
    ) -> bool:
        return conn.execute(
            READY_RUN_FIX,
            {"run_id": run_id, "new_uuid": ready_run_id},
        ).rowcount == 1


def _ref(
    key: str | None,
    content_hash: str | None,
    size: int | None,
) -> ObjectRef | None:
    if key is None or content_hash is None or size is None:
        return None

    return ObjectRef(
        object_key=key,
        content_hash=content_hash,
        size_bytes=size,
    )
