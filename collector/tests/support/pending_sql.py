"""송신, 수집, probe 단계의 상태 전이 SQL

소유 모듈(transport gate, crawl repository, registry probe)이 생기면 그 모듈 상수로 옮기고
test_sql_statements가 그 상수를 실행
"""

RECORD_PROBE = """
UPDATE collector.sources
SET probe_state = %(state)s, probe_version = %(version)s, probe_raw_hash = %(raw_hash)s,
    probed_at = clock_timestamp()
WHERE source_id = %(source_id)s AND approval_state = 'BLOCKED'
  AND definition_hash = %(probed_hash)s
RETURNING source_id
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

# 예약 TX 2단계: job 송신은 APPROVED, probe는 BLOCKED만
RESERVE_APPROVAL = """
SELECT approval_state
FROM collector.sources
WHERE source_id = %(source_id)s
  AND approval_state = CASE WHEN %(is_probe)s::boolean THEN 'BLOCKED' ELSE 'APPROVED' END
FOR SHARE
"""

# 예약 TX 3단계: http_sends보다 host 행을 먼저 잠금
RESERVE_HOST_INSERT = """
INSERT INTO collector.host_states (host) VALUES (%(host)s) ON CONFLICT DO NOTHING
"""

RESERVE_HOST_LOCK = """
SELECT consecutive_failures, circuit_level, half_open, throttle_level,
       robots_status, robots_body, robots_fetched_at,
       extract(epoch FROM next_allowed_at - clock_timestamp()) AS wait_left_seconds,
       extract(epoch FROM circuit_open_until - clock_timestamp()) AS circuit_left_seconds
FROM collector.host_states
WHERE host = %(host)s
FOR UPDATE
"""

# 예약 TX 4단계: 만료된 진행 중 송신 회수
RESERVE_EXPIRE = """
UPDATE collector.http_sends
SET state = 'UNKNOWN', finished_at = clock_timestamp()
WHERE state = 'SENDING'
  AND reserved_at < clock_timestamp() - make_interval(secs => %(in_flight_expiry_seconds)s)
"""

# 예약 TX 5단계: 문서 시도
RESERVE_ATTEMPT = """
UPDATE collector.collection_targets
SET state = 'SENDING', attempt_count = attempt_count + 1, sending_epoch = %(epoch)s
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND state = 'PENDING' AND attempt_count < attempt_cap
  AND coalesce(next_attempt_at, '-infinity') <= clock_timestamp()
RETURNING attempt_count
"""

# 예약 TX 6단계: 송신 원장
RESERVE_SEND = """
INSERT INTO collector.http_sends
    (budget_limit, host, job_id, lease_epoch, probe_source, purpose, document_id, path)
VALUES (%(budget_limit)s, %(host)s, %(job_id)s, %(epoch)s,
        %(probe_source)s, %(purpose)s, %(document_id)s, %(path)s)
RETURNING send_id
"""

# 결과 TX 1단계: 예약 TX와 같은 순서로 host 행 먼저
RESULT_HOST_LOCK = """
SELECT 1 FROM collector.host_states WHERE host = %(host)s FOR UPDATE
"""

RESULT_SENT = """
UPDATE collector.http_sends
SET state = 'SENT', http_status = %(status)s, failure_kind = %(kind)s, finished_at = clock_timestamp()
WHERE send_id = %(send_id)s AND state = 'SENDING'
"""

# 중단된 송신, host 상태는 갱신하지 않음
RESULT_UNKNOWN = """
UPDATE collector.http_sends
SET state = 'UNKNOWN', finished_at = clock_timestamp()
WHERE send_id = %(send_id)s AND state = 'SENDING'
"""

RESULT_HOST_UPDATE = """
UPDATE collector.host_states
SET next_allowed_at = clock_timestamp() + make_interval(secs => %(wait_seconds)s),
    consecutive_failures = %(failures)s,
    circuit_level = %(level)s,
    circuit_open_until = %(open_until)s,
    half_open = %(half_open)s,
    throttle_level = %(throttle)s,
    robots_status = coalesce(%(robots_status)s::integer, robots_status),
    robots_body = CASE WHEN %(robots_status)s::integer IS NULL THEN robots_body ELSE %(robots_body)s::text END,
    robots_fetched_at = CASE WHEN %(robots_status)s::integer IS NULL THEN robots_fetched_at ELSE clock_timestamp() END
WHERE host = %(host)s
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

TARGET_RETRY = """
UPDATE collector.collection_targets
SET state = 'PENDING', sending_epoch = NULL,
    next_attempt_at = clock_timestamp() + make_interval(secs => %(delay_seconds)s)
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND state = 'SENDING' AND sending_epoch = %(epoch)s
"""

TARGET_FAILED = """
UPDATE collector.collection_targets
SET state = 'FAILED', sending_epoch = NULL,
    error_code = %(error_code)s, needs_review = %(needs_review)s,
    failed_at = date_trunc('second', clock_timestamp()),
    http_status = %(status)s, observed_at = %(observed_at)s
WHERE run_id = %(run_id)s AND document_id = %(document_id)s
  AND state = 'SENDING' AND sending_epoch = %(epoch)s
"""

# 닫힘 검증 위반
RUN_DRIFT = """
UPDATE collector.collection_runs SET drift_detected = true WHERE run_id = %(run_id)s
"""

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
WHERE run_id = %(run_id)s AND ready_key IS NULL
"""
