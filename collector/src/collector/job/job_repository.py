"""collector.jobs SQL: 접수, claim, fence, heartbeat, recover, 종결, 취소, 재개"""

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from psycopg.types.json import Jsonb

from collector.contract.canonical_json import JsonValue
from collector.db import Conn
from collector.store.object_store import ObjectRef

STATUS_COLUMNS = """job_id, request_id, state, wait_reason, attempt_count, lease_epoch, next_attempt_at,
          updated_at, result_key, result_hash, result_size, last_error_code, cancel_requested"""

INSERT_JOB = """
INSERT INTO collector.jobs (job_id, request_id, request_hash, request_body)
VALUES (%(job_id)s, %(request_id)s, %(request_hash)s, %(body)s)
ON CONFLICT (request_id) DO NOTHING
RETURNING job_id
"""

SELECT_JOB_BY_REQUEST = """
SELECT job_id, request_hash, state, created_at
FROM collector.jobs
WHERE request_id = %(request_id)s
"""

SELECT_STATUS = f"""
SELECT {STATUS_COLUMNS}
FROM collector.jobs
WHERE job_id = %(job_id)s
"""

# 문서, batch, run을 바꾸는 모든 TX의 첫 문장
FENCE = """
SELECT cancel_requested
FROM collector.jobs
WHERE job_id = %(job_id)s
  AND lease_owner = %(owner_id)s
  AND lease_epoch = %(epoch)s
  AND state = 'RUNNING'
  AND lease_until > clock_timestamp()
FOR SHARE
"""

# 취소 요청됐는데 lease가 끝난 job 정리
CANCEL_EXPIRED = """
UPDATE collector.jobs
SET state = 'CANCELLED', lease_until = NULL, updated_at = clock_timestamp()
WHERE state = 'RUNNING' AND cancel_requested AND lease_until < clock_timestamp()
"""

CLAIM = """
UPDATE collector.jobs j
SET state = 'RUNNING',
    lease_owner = %(owner_id)s,
    lease_epoch = j.lease_epoch + 1,
    lease_until = clock_timestamp() + make_interval(secs => %(lease_seconds)s),
    attempt_count = j.attempt_count + 1,
    wait_reason = NULL,
    next_attempt_at = NULL,
    updated_at = clock_timestamp()
WHERE j.job_id = (
    SELECT job_id
    FROM collector.jobs
    WHERE NOT cancel_requested
      AND ((state IN ('QUEUED', 'RETRY_WAIT')
            AND coalesce(next_attempt_at, '-infinity') <= clock_timestamp())
           OR (state = 'RUNNING' AND lease_until < clock_timestamp()))
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING j.job_id, j.lease_epoch, j.request_body
"""

# work 대기 계산, 취소 요청된 RUNNING도 lease 만료 뒤 정리될 때까지 대기로 셈
NEXT_DUE = """
SELECT count(*) AS pending,
       extract(epoch FROM min(CASE WHEN state = 'RETRY_WAIT' THEN next_attempt_at
                                   WHEN state = 'QUEUED' THEN clock_timestamp()
                                   WHEN state = 'RUNNING' THEN lease_until END)
                          - clock_timestamp()) AS next_due_seconds
FROM collector.jobs
WHERE (state IN ('QUEUED', 'RETRY_WAIT') AND NOT cancel_requested)
   OR state = 'RUNNING'
"""

HEARTBEAT = """
UPDATE collector.jobs
SET lease_until = clock_timestamp() + make_interval(secs => %(lease_seconds)s),
    updated_at = clock_timestamp()
WHERE job_id = %(job_id)s AND lease_owner = %(owner_id)s AND lease_epoch = %(epoch)s
  AND state = 'RUNNING' AND lease_until > clock_timestamp()
RETURNING lease_until
"""

# 잠금 순서: 남은 SENDING 행의 host 행을 먼저
RECOVER_LOCK_HOSTS = """
SELECT 1
FROM collector.host_states
WHERE host IN (SELECT host FROM collector.http_sends
               WHERE job_id = %(job_id)s AND state = 'SENDING' AND lease_epoch < %(epoch)s)
ORDER BY host
FOR UPDATE
"""

RECOVER_SENDS = """
UPDATE collector.http_sends
SET state = 'UNKNOWN', finished_at = clock_timestamp()
WHERE job_id = %(job_id)s AND state = 'SENDING' AND lease_epoch < %(epoch)s
"""

RECOVER_TARGETS = """
UPDATE collector.collection_targets
SET state = CASE WHEN attempt_count >= attempt_cap THEN 'FAILED' ELSE 'PENDING' END,
    sending_epoch = NULL,
    next_attempt_at = CASE WHEN attempt_count >= attempt_cap THEN NULL
                           ELSE clock_timestamp() + make_interval(secs => %(recover_delay_seconds)s) END,
    error_code = CASE WHEN attempt_count >= attempt_cap THEN 'HTTP_RETRY_EXHAUSTED' END,
    needs_review = CASE WHEN attempt_count >= attempt_cap THEN true END,
    failed_at = CASE WHEN attempt_count >= attempt_cap THEN date_trunc('second', clock_timestamp()) END
WHERE run_id = %(run_id)s AND state = 'SENDING' AND sending_epoch < %(epoch)s
"""

LEASE_HELD = """
WHERE job_id = %(job_id)s AND lease_owner = %(owner_id)s AND lease_epoch = %(epoch)s
  AND state = 'RUNNING' AND lease_until > clock_timestamp()"""

SUCCEED = f"""
UPDATE collector.jobs
SET state = 'SUCCEEDED', result_key = %(key)s, result_hash = %(hash)s, result_size = %(size)s,
    lease_until = NULL, updated_at = clock_timestamp()
{LEASE_HELD} AND NOT cancel_requested
RETURNING job_id
"""

# 지연이 NULL이면 다음 KST 00:00, 대기 시각은 PG 시계로 계산
FINISH_WAITING = f"""
UPDATE collector.jobs
SET state = 'RETRY_WAIT', wait_reason = %(reason)s,
    next_attempt_at = CASE WHEN %(delay)s::float8 IS NULL
                           THEN ((date_trunc('day', clock_timestamp() AT TIME ZONE 'Asia/Seoul') + interval '1 day')
                                 AT TIME ZONE 'Asia/Seoul')
                           ELSE clock_timestamp() + make_interval(secs => %(delay)s::float8) END,
    lease_until = NULL, updated_at = clock_timestamp()
{LEASE_HELD} AND NOT cancel_requested
RETURNING {STATUS_COLUMNS}
"""

# NEEDS_REVIEW 또는 FAILED_FINAL
FINISH_STOPPED = f"""
UPDATE collector.jobs
SET state = %(state)s, last_error_code = %(code)s, lease_until = NULL, updated_at = clock_timestamp()
{LEASE_HELD} AND NOT cancel_requested
RETURNING {STATUS_COLUMNS}
"""

FINISH_CANCELLED = f"""
UPDATE collector.jobs
SET state = 'CANCELLED', lease_until = NULL, updated_at = clock_timestamp()
{LEASE_HELD}
RETURNING {STATUS_COLUMNS}
"""

CANCEL = f"""
UPDATE collector.jobs
SET cancel_requested = true,
    state = CASE WHEN state IN ('QUEUED', 'RETRY_WAIT', 'NEEDS_REVIEW', 'FAILED_FINAL')
                   OR (state = 'RUNNING' AND lease_until < clock_timestamp())
                 THEN 'CANCELLED' ELSE state END,
    wait_reason = CASE WHEN state = 'RETRY_WAIT' THEN NULL ELSE wait_reason END,
    lease_until = CASE WHEN state = 'RUNNING' AND lease_until >= clock_timestamp()
                       THEN lease_until END,
    updated_at = clock_timestamp()
WHERE job_id = %(job_id)s AND state NOT IN ('SUCCEEDED', 'CANCELLED')
RETURNING {STATUS_COLUMNS}
"""

RESUME = f"""
UPDATE collector.jobs
SET state = 'QUEUED', wait_reason = NULL, next_attempt_at = NULL, last_error_code = NULL,
    updated_at = clock_timestamp()
WHERE job_id = %(job_id)s AND state IN ('NEEDS_REVIEW', 'FAILED_FINAL') AND NOT cancel_requested
  AND last_error_code <> 'SCOPE_MISMATCH'
  AND NOT EXISTS (SELECT 1
                  FROM jsonb_array_elements(request_body -> 'inputs') AS i
                  LEFT JOIN collector.sources s ON s.source_id = i ->> 'sourceId'
                  WHERE s.approval_state IS DISTINCT FROM 'APPROVED')
RETURNING {STATUS_COLUMNS}
"""

type StatusRow = tuple[
    UUID,
    UUID,
    str,
    str | None,
    int,
    int,
    datetime | None,
    datetime,
    str | None,
    str | None,
    int | None,
    str | None,
    bool,
]


@dataclass(frozen=True)
class Lease:
    job_id: UUID
    owner_id: UUID
    epoch: int

    def params(self) -> dict[str, UUID | int]:
        return {
            "job_id": self.job_id,
            "owner_id": self.owner_id,
            "epoch": self.epoch,
        }


class LeaseLost(Exception):
    """fence 조건이 맞지 않음, 이 owner는 더 쓰지 않음"""


class CancelRequested(Exception):
    """lease는 유효하지만 취소 요청됨"""


class JobRepository:
    def insert(
        self,
        conn: Conn,
        job_id: UUID,
        request_id: UUID,
        request_hash: str,
        body: Mapping[str, JsonValue],
    ) -> bool:
        """이 호출이 행을 넣었으면 True"""

        row = (
            conn
            .execute(
                INSERT_JOB,
                {
                    "job_id": job_id,
                    "request_id": request_id,
                    "request_hash": request_hash,
                    "body": Jsonb(dict(body)),
                },
            )
            .fetchone()
        )
        return row is not None

    def by_request(
        self,
        conn: Conn,
        request_id: UUID,
    ) -> tuple[UUID, str, str, datetime]:
        row = (
            conn
            .execute(
                SELECT_JOB_BY_REQUEST,
                {"request_id": request_id},
            )
            .fetchone()
        )

        if row is None:
            raise LookupError(request_id)

        return row

    def status(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> StatusRow | None:
        return (
            conn
            .execute(
                SELECT_STATUS,
                {"job_id": job_id},
            )
            .fetchone()
        )

    def fence(
        self,
        conn: Conn,
        lease: Lease,
    ) -> None:
        """0 row면 LeaseLost, 취소 요청됐으면 CancelRequested"""

        row = (
            conn
            .execute(FENCE, lease.params())
            .fetchone()
        )

        if row is None:
            raise LeaseLost(lease.job_id)

        if row[0]:
            raise CancelRequested(lease.job_id)

    def claim(
        self,
        conn: Conn,
        owner_id: UUID,
        lease_seconds: float,
    ) -> tuple[UUID, int, Mapping[str, JsonValue]] | None:
        conn.execute(CANCEL_EXPIRED)
        return (
            conn
            .execute(
                CLAIM,
                {
                    "owner_id": owner_id,
                    "lease_seconds": lease_seconds,
                },
            )
            .fetchone()
        )

    def next_due(self, conn: Conn) -> tuple[int, float | None]:
        """대기 job 수와 가장 이른 기한까지 PG가 계산한 남은 초"""

        row = (
            conn
            .execute(NEXT_DUE)
            .fetchone()
        )
        assert row is not None
        return row[0], None if row[1] is None else float(row[1])

    def heartbeat(
        self,
        conn: Conn,
        lease: Lease,
        lease_seconds: float,
    ) -> bool:
        row = (
            conn
            .execute(
                HEARTBEAT,
                {
                    **lease.params(),
                    "lease_seconds": lease_seconds,
                },
            )
            .fetchone()
        )
        return row is not None

    def recover(
        self,
        conn: Conn,
        lease: Lease,
        run_id: UUID | None,
        recover_delay_seconds: float,
    ) -> None:
        """이전 epoch가 남긴 SENDING 송신과 문서 정리, 시도와 예산은 돌려주지 않음"""

        params = {
            "job_id": lease.job_id,
            "epoch": lease.epoch,
            "run_id": run_id,
            "recover_delay_seconds": recover_delay_seconds,
        }
        conn.execute(RECOVER_LOCK_HOSTS, params)
        conn.execute(RECOVER_SENDS, params)

        if run_id is not None:
            conn.execute(RECOVER_TARGETS, params)

    def succeed(
        self,
        conn: Conn,
        lease: Lease,
        result: ObjectRef,
    ) -> None:
        """0 row면 LeaseLost, 같은 TX의 결과 기록도 rollback"""

        row = (
            conn
            .execute(
                SUCCEED,
                {
                    **lease.params(),
                    "key": result.object_key,
                    "hash": result.content_hash,
                    "size": result.size_bytes,
                },
            )
            .fetchone()
        )

        if row is None:
            raise LeaseLost(lease.job_id)

    def finish_waiting(
        self,
        conn: Conn,
        lease: Lease,
        reason: str,
        delay_seconds: float | None,
    ) -> StatusRow | None:
        return (
            conn
            .execute(
                FINISH_WAITING,
                {
                    **lease.params(),
                    "reason": reason,
                    "delay": delay_seconds,
                },
            )
            .fetchone()
        )

    def finish_stopped(
        self,
        conn: Conn,
        lease: Lease,
        state: str,
        code: str,
    ) -> StatusRow | None:
        return (
            conn
            .execute(
                FINISH_STOPPED,
                {
                    **lease.params(),
                    "state": state,
                    "code": code,
                },
            )
            .fetchone()
        )

    def finish_cancelled(
        self,
        conn: Conn,
        lease: Lease,
    ) -> StatusRow | None:
        return (
            conn
            .execute(FINISH_CANCELLED, lease.params())
            .fetchone()
        )

    def cancel(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> StatusRow | None:
        return (
            conn
            .execute(
                CANCEL,
                {"job_id": job_id},
            )
            .fetchone()
        )

    def resume(
        self,
        conn: Conn,
        job_id: UUID,
    ) -> StatusRow | None:
        return (
            conn
            .execute(
                RESUME,
                {"job_id": job_id},
            )
            .fetchone()
        )
