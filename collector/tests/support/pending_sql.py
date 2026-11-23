"""수집 단계의 상태 전이 SQL

소유 모듈(crawl repository, worker)이 생기면 그 모듈 상수로 옮기고
test_sql_statements가 그 상수를 실행
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
