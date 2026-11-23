-- collector 스키마: 표 8개, 시퀀스 2개, 불변 규칙은 trigger와 CHECK
-- 정책 수치(시도 상한, 예산, 지연)는 CrawlPolicy가 매개변수·행 열로 넘기고 이 파일에 상수로 두지 않음
-- 스키마 collector는 Flyway -schemas=collector가 만든다 (로컬과 CI는 -createSchemas=true, 배포 환경은 false)

-- 변경 거부 공용 함수
CREATE FUNCTION collector.reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows must not be changed by %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

-- 값이 들어간 열은 다시 바꿀 수 없음, trigger 인자로 적은 열만 예외
-- 행마다 열 수만큼 비교, 수만 행 규모가 되면 열별 비교로 바꿈
-- 주의: BEFORE trigger의 NEW에서 생성 열은 NULL, 이 함수를 붙인 표(runs, batches)에는 생성 열이 없음, 생기면 TG_ARGV에 넣음
CREATE FUNCTION collector.set_once_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    old_row jsonb := to_jsonb(OLD);
    new_row jsonb := to_jsonb(NEW);
    column_name text;
BEGIN
    FOR column_name IN SELECT jsonb_object_keys(old_row) LOOP
        IF old_row -> column_name <> 'null'::jsonb
           AND new_row -> column_name IS DISTINCT FROM old_row -> column_name
           AND NOT (column_name = ANY (coalesce(TG_ARGV, ARRAY[]::text[]))) THEN
            RAISE EXCEPTION '%.% is set-once', TG_TABLE_NAME, column_name
                USING ERRCODE = 'restrict_violation';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;

-- 승인 source registry, 정의는 import 파일의 entry JSON 그대로, 승인 전 BLOCKED
CREATE TABLE collector.sources (
    source_id          text PRIMARY KEY CHECK (source_id ~ '^[a-z0-9][a-z0-9.-]{0,127}$'),
    definition         jsonb NOT NULL CHECK (definition ->> 'sourceId' = source_id),
    definition_hash    text NOT NULL CHECK (definition_hash ~ '^sha256:[0-9a-f]{64}$'),
    probe_state        text NOT NULL DEFAULT 'NONE' CHECK (probe_state IN ('NONE', 'PASSED', 'FAILED')),
    probe_version      text,                       -- entry meta[name=version] 관측값
    probe_raw_hash     text CHECK (probe_raw_hash ~ '^sha256:[0-9a-f]{64}$'),
    probed_at          timestamptz,
    approval_state     text NOT NULL DEFAULT 'BLOCKED'
                       CHECK (approval_state IN ('BLOCKED', 'APPROVED', 'REVOKED')),
    approved_by        text,
    approved_at        timestamptz,
    parser_profile_ref text,                       -- pipeline parser 프로필 증거, 예 spring-antora-reference@2
    revoked_by         text,
    revoked_at         timestamptz,
    CHECK ((probe_state = 'NONE') = (probed_at IS NULL)),
    CHECK (probe_state <> 'PASSED' OR (probe_version IS NOT NULL AND probe_raw_hash IS NOT NULL)),
    CHECK ((approval_state = 'BLOCKED') = (approved_by IS NULL)),
    CHECK (approval_state = 'BLOCKED' OR (approved_at IS NOT NULL AND parser_profile_ref IS NOT NULL)),
    CHECK (approval_state <> 'APPROVED'
           OR (probe_state = 'PASSED' AND probe_version = definition ->> 'exactVersion')),
    CHECK ((approval_state = 'REVOKED') = (revoked_by IS NOT NULL AND revoked_at IS NOT NULL))
);

-- 승인된 행은 철회만, 철회된 행은 고정 (sources에는 생성 열이 없음, 생기면 제외 목록에 넣음)
CREATE FUNCTION collector.sources_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.approval_state = 'REVOKED'
       OR (OLD.approval_state = 'APPROVED'
           AND (NEW.approval_state <> 'REVOKED'
                OR (to_jsonb(NEW) - ARRAY['approval_state', 'revoked_by', 'revoked_at'])
                   <> (to_jsonb(OLD) - ARRAY['approval_state', 'revoked_by', 'revoked_at']))) THEN
        RAISE EXCEPTION 'approved source % is immutable except revocation', OLD.source_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER sources_guard
    BEFORE UPDATE ON collector.sources
    FOR EACH ROW EXECUTE FUNCTION collector.sources_guard();

CREATE TRIGGER sources_no_delete
    BEFORE DELETE ON collector.sources
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- 작업 원장과 durable queue, 임대 열
CREATE TABLE collector.jobs (
    job_id           uuid PRIMARY KEY,
    request_id       uuid NOT NULL UNIQUE,
    request_hash     text NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    request_body     jsonb NOT NULL,
    state            text NOT NULL DEFAULT 'QUEUED'
                     CHECK (state IN ('QUEUED', 'RETRY_WAIT', 'RUNNING', 'SUCCEEDED',
                                      'FAILED_FINAL', 'NEEDS_REVIEW', 'CANCELLED')),
    wait_reason      text CHECK (wait_reason IN ('INPUT', 'BUDGET', 'HOST', 'RETRY', 'SHUTDOWN')),  -- INPUT은 계약 enum 일치용, collector는 만들지 않음
    attempt_count    integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_owner      uuid,
    lease_epoch      bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_until      timestamptz,
    next_attempt_at  timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    last_error_code  text,
    result_key       text CHECK (result_key ~ '^manifests/'),
    result_hash      text CHECK (result_hash ~ '^sha256:[0-9a-f]{64}$'),
    result_size      bigint CHECK (result_size >= 0),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (state <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (state <> 'RETRY_WAIT' OR (next_attempt_at IS NOT NULL AND wait_reason IS NOT NULL)),
    CHECK ((state = 'RETRY_WAIT') = (wait_reason IS NOT NULL)),
    CHECK ((state = 'SUCCEEDED') = (result_key IS NOT NULL)),
    CHECK ((result_key IS NULL) = (result_hash IS NULL) AND (result_key IS NULL) = (result_size IS NULL)),
    CHECK (state NOT IN ('NEEDS_REVIEW', 'FAILED_FINAL') OR last_error_code IS NOT NULL)
);

CREATE INDEX jobs_claimable ON collector.jobs (created_at)
    WHERE state IN ('QUEUED', 'RETRY_WAIT', 'RUNNING');

-- 시작 모양, 식별 열 불변, 시도와 epoch 단조 증가, 취소 요청은 되돌릴 수 없음, 허용 전이만, 종결 행 고정
CREATE FUNCTION collector.jobs_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'QUEUED' OR NEW.attempt_count <> 0 OR NEW.lease_epoch <> 0 THEN
            RAISE EXCEPTION 'a job starts QUEUED with zero attempts'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal job % must not change', OLD.job_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.job_id, NEW.request_id, NEW.request_hash, NEW.created_at)
           IS DISTINCT FROM (OLD.job_id, OLD.request_id, OLD.request_hash, OLD.created_at)
       OR NEW.request_body <> OLD.request_body THEN
        RAISE EXCEPTION 'job identity must not change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count OR NEW.lease_epoch < OLD.lease_epoch THEN
        RAISE EXCEPTION 'attempt_count and lease_epoch must not decrease'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.cancel_requested AND NOT NEW.cancel_requested THEN
        RAISE EXCEPTION 'cancel request of job % must not be withdrawn', OLD.job_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
           (OLD.state IN ('QUEUED', 'RETRY_WAIT') AND NEW.state IN ('RUNNING', 'CANCELLED'))
        OR (OLD.state = 'RUNNING' AND NEW.state <> 'QUEUED')
        OR (OLD.state IN ('NEEDS_REVIEW', 'FAILED_FINAL') AND NEW.state IN ('QUEUED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'invalid job transition % -> %', OLD.state, NEW.state
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER jobs_guard
    BEFORE INSERT OR UPDATE ON collector.jobs
    FOR EACH ROW EXECUTE FUNCTION collector.jobs_guard();

CREATE TRIGGER jobs_no_delete
    BEFORE DELETE ON collector.jobs
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- readinessSequence, observationSequence: 단조 증가, 구멍 허용 (nextval은 rollback되지 않음)
CREATE SEQUENCE collector.readiness_sequence MINVALUE 1 MAXVALUE 9007199254740991 NO CYCLE;
CREATE SEQUENCE collector.observation_sequence MINVALUE 1 MAXVALUE 9007199254740991 NO CYCLE;

-- 수집 회차, job 하나에 run 하나
CREATE TABLE collector.collection_runs (
    run_id               uuid PRIMARY KEY,
    job_id               uuid NOT NULL UNIQUE REFERENCES collector.jobs,
    snapshot_id          uuid NOT NULL UNIQUE,
    scope_hash           text NOT NULL CHECK (scope_hash ~ '^sha256:[0-9a-f]{64}$'),
    source_registry_hash text NOT NULL CHECK (source_registry_hash ~ '^sha256:[0-9a-f]{64}$'),
    observation_sequence bigint NOT NULL CHECK (observation_sequence BETWEEN 1 AND 9007199254740991),
    started_at           timestamptz NOT NULL DEFAULT date_trunc('second', clock_timestamp()),
    discovery_state      text NOT NULL DEFAULT 'DISCOVERING'
                         CHECK (discovery_state IN ('DISCOVERING', 'CLOSED', 'SEALED')),
    official_revision    text,                     -- entry 편집 링크의 tag, 예 v4.1.3
    sealed_at            timestamptz,              -- CLOSED 전이 TX에서 초 단위로 고정, discovery bytes에 들어감
    targets_key          text,                     -- 회차 targets.jsonl
    targets_hash         text,
    targets_size         bigint,
    discovery_key        text,
    discovery_hash       text,
    discovery_size       bigint,
    drift_detected       boolean NOT NULL DEFAULT false,
    ready_run_id         uuid UNIQUE,
    readiness_sequence   bigint UNIQUE CHECK (readiness_sequence BETWEEN 1 AND 9007199254740991),
    ready_created_at     timestamptz,
    ready_key            text,
    ready_hash           text,
    ready_size           bigint,
    CHECK ((discovery_state = 'DISCOVERING') = (sealed_at IS NULL)),
    CHECK ((targets_key IS NULL) = (targets_hash IS NULL) AND (targets_key IS NULL) = (targets_size IS NULL)),
    CHECK ((discovery_key IS NULL) = (discovery_hash IS NULL) AND (discovery_key IS NULL) = (discovery_size IS NULL)),
    CHECK ((discovery_state = 'SEALED') = (discovery_key IS NOT NULL)),
    CHECK (discovery_key IS NULL OR targets_key IS NOT NULL),
    CHECK ((ready_run_id IS NULL) = (readiness_sequence IS NULL)
           AND (ready_run_id IS NULL) = (ready_created_at IS NULL)),
    CHECK ((ready_key IS NULL) = (ready_hash IS NULL) AND (ready_key IS NULL) = (ready_size IS NULL)),
    CHECK (ready_key IS NULL OR ready_run_id IS NOT NULL),
    CHECK (ready_run_id IS NULL OR (discovery_state = 'SEALED' AND NOT drift_detected))
);

CREATE TRIGGER collection_runs_set_once
    BEFORE UPDATE ON collector.collection_runs
    FOR EACH ROW EXECUTE FUNCTION collector.set_once_guard('discovery_state', 'drift_detected');

-- 발견 상태는 앞으로만, drift는 켜지기만
CREATE FUNCTION collector.collection_runs_forward() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF array_position(ARRAY['DISCOVERING', 'CLOSED', 'SEALED'], NEW.discovery_state)
           < array_position(ARRAY['DISCOVERING', 'CLOSED', 'SEALED'], OLD.discovery_state)
       OR (OLD.drift_detected AND NOT NEW.drift_detected) THEN
        RAISE EXCEPTION 'run % must only move forward', OLD.run_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER collection_runs_forward
    BEFORE UPDATE ON collector.collection_runs
    FOR EACH ROW EXECUTE FUNCTION collector.collection_runs_forward();

CREATE TRIGGER collection_runs_no_delete
    BEFORE DELETE ON collector.collection_runs
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- (project, exactVersion, applicability)별 Pack 범위, run 생성 TX에서 고정
CREATE TABLE collector.collection_projections (
    run_id               uuid NOT NULL REFERENCES collector.collection_runs,
    project              text NOT NULL,
    exact_version        text NOT NULL,
    applicability        text NOT NULL CHECK (applicability = 'EXACT_RELEASE'),
    input_scope_key      text NOT NULL CHECK (input_scope_key ~ '^sha256:[0-9a-f]{64}$'),
    observation_sequence bigint NOT NULL CHECK (observation_sequence BETWEEN 1 AND 9007199254740991),
    PRIMARY KEY (run_id, project, exact_version)
);

CREATE TRIGGER collection_projections_insert_only
    BEFORE UPDATE OR DELETE ON collector.collection_projections
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- 100개 이하 전달 묶음, 봉인 값은 한 번만
CREATE TABLE collector.collection_batches (
    batch_id       uuid PRIMARY KEY,
    run_id         uuid NOT NULL REFERENCES collector.collection_runs,
    ordinal        integer NOT NULL CHECK (ordinal >= 0),
    project        text NOT NULL,
    exact_version  text NOT NULL,
    document_type  text NOT NULL,
    target_count   integer NOT NULL CHECK (target_count BETWEEN 1 AND 100),
    targets_key    text,
    targets_hash   text,
    targets_size   bigint,
    sealed_at      timestamptz,                    -- 봉인 TX에서 초 단위로 고정, envelope bytes에 들어감
    sealed_epoch   bigint,                         -- envelope leaseEpoch = 봉인한 owner의 epoch
    success_count  integer CHECK (success_count >= 0),
    failure_count  integer CHECK (failure_count >= 0),
    outcomes_key   text,
    outcomes_hash  text,
    outcomes_size  bigint,
    envelope_key   text,
    envelope_hash  text,
    envelope_size  bigint,
    UNIQUE (run_id, ordinal),
    CHECK ((sealed_at IS NULL) = (sealed_epoch IS NULL)
           AND (sealed_at IS NULL) = (success_count IS NULL)
           AND (sealed_at IS NULL) = (failure_count IS NULL)),
    CHECK (sealed_at IS NULL OR success_count + failure_count = target_count),
    CHECK ((targets_key IS NULL) = (targets_hash IS NULL) AND (targets_key IS NULL) = (targets_size IS NULL)),
    CHECK ((outcomes_key IS NULL) = (outcomes_hash IS NULL) AND (outcomes_key IS NULL) = (outcomes_size IS NULL)),
    CHECK ((envelope_key IS NULL) = (envelope_hash IS NULL) AND (envelope_key IS NULL) = (envelope_size IS NULL)),
    CHECK (outcomes_key IS NULL OR sealed_at IS NOT NULL),
    CHECK (envelope_key IS NULL OR (outcomes_key IS NOT NULL AND targets_key IS NOT NULL))
);

CREATE TRIGGER collection_batches_set_once
    BEFORE UPDATE ON collector.collection_batches
    FOR EACH ROW EXECUTE FUNCTION collector.set_once_guard();

CREATE TRIGGER collection_batches_no_delete
    BEFORE DELETE ON collector.collection_batches
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- 문서 하나 = 행 하나: 대상, 송신 예약 상태, 결과
CREATE TABLE collector.collection_targets (
    run_id           uuid NOT NULL REFERENCES collector.collection_runs,
    document_id      text NOT NULL CHECK (document_id ~ '^sha256:[0-9a-f]{64}$'),
    batch_id         uuid NOT NULL REFERENCES collector.collection_batches,
    source_id        text NOT NULL REFERENCES collector.sources,
    url              text NOT NULL CHECK (url ~ '^https://[^/?#@]+/[^?#]*$'),
    state            text NOT NULL DEFAULT 'PENDING'
                     CHECK (state IN ('PENDING', 'SENDING', 'SUCCEEDED', 'FAILED')),
    attempt_cap      smallint NOT NULL CHECK (attempt_cap BETWEEN 1 AND 3),  -- 닫기 TX가 정책 값을 기록, 3은 계약 attemptCount 최대값
    attempt_count    integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at  timestamptz,
    sending_epoch    bigint,
    raw_key          text CHECK (raw_key ~ '^raw/[a-z][a-z0-9-]*/[0-9]+\.[0-9]+\.[0-9]+(\.RELEASE)?/[0-9a-f]{64}\.html$'),  -- 버전은 contracts/v1 exactVersion 패턴, 계약이 넓어지면 V002
    raw_hash         text CHECK (raw_hash ~ '^sha256:[0-9a-f]{64}$'),
    raw_size         bigint CHECK (raw_size >= 0),
    http_status      integer,
    final_url        text CHECK (final_url ~ '^https://'),
    content_type     text,
    last_modified    text,
    etag             text,
    observed_version text,                         -- meta[name=version], 판정 아님
    versioned_url    text,                         -- meta[name=versioned-url]
    source_tag       text,                         -- 편집 링크 tag
    observed_at      timestamptz,                  -- 응답 수신 PG 시각, 초 단위
    error_code       text,
    needs_review     boolean,
    failed_at        timestamptz,
    PRIMARY KEY (run_id, document_id),
    CHECK (attempt_count <= attempt_cap),
    CHECK ((state = 'SENDING') = (sending_epoch IS NOT NULL)),
    CHECK ((state = 'SUCCEEDED') = (raw_key IS NOT NULL AND raw_hash IS NOT NULL AND raw_size IS NOT NULL
                                    AND observed_at IS NOT NULL AND final_url IS NOT NULL)),
    CHECK (raw_key IS NULL OR substr(raw_key, length(raw_key) - 68, 64) = substr(raw_hash, 8)),
    CHECK ((state = 'FAILED') = (error_code IS NOT NULL AND failed_at IS NOT NULL AND needs_review IS NOT NULL))
);

CREATE INDEX collection_targets_due ON collector.collection_targets (run_id, next_attempt_at)
    WHERE state = 'PENDING';

-- 시도 단조 증가, 종결 행 불변, 봉인 batch 행 고정, 식별 열 불변
CREATE FUNCTION collector.collection_targets_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'attempt_count must not decrease'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal target % must not change', OLD.document_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM collector.collection_batches
               WHERE batch_id = OLD.batch_id AND sealed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'target of a sealed batch must not change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.run_id, NEW.document_id, NEW.batch_id, NEW.source_id, NEW.url, NEW.attempt_cap)
           IS DISTINCT FROM (OLD.run_id, OLD.document_id, OLD.batch_id, OLD.source_id, OLD.url, OLD.attempt_cap) THEN
        RAISE EXCEPTION 'target identity must not change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER collection_targets_guard
    BEFORE UPDATE ON collector.collection_targets
    FOR EACH ROW EXECUTE FUNCTION collector.collection_targets_guard();

CREATE TRIGGER collection_targets_no_delete
    BEFORE DELETE ON collector.collection_targets
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- 외부 HTTP 송신 원장 = 일일 예산의 근거 = 요청 시도 로그
CREATE TABLE collector.http_sends (
    send_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reserved_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    budget_day   date NOT NULL GENERATED ALWAYS AS ((reserved_at AT TIME ZONE 'Asia/Seoul')::date) STORED,
    budget_limit integer NOT NULL CHECK (budget_limit >= 1),   -- 예약 시점 CrawlPolicy 일일 한도
    host         text NOT NULL,
    job_id       uuid REFERENCES collector.jobs,  -- NULL = registry probe
    lease_epoch  bigint,
    probe_source text REFERENCES collector.sources,
    purpose      text NOT NULL CHECK (purpose IN ('ROBOTS', 'DISCOVERY', 'DOCUMENT')),
    document_id  text,
    path         text NOT NULL,                    -- host 뒤 path만, query와 본문 없음
    state        text NOT NULL DEFAULT 'SENDING' CHECK (state IN ('SENDING', 'SENT', 'UNKNOWN')),
    http_status  integer,
    failure_kind text CHECK (failure_kind IN ('CONNECT', 'TLS', 'TIMEOUT', 'TOO_LARGE', 'ENCODING', 'DENIED', 'INTERNAL')),
    finished_at  timestamptz,
    CHECK ((job_id IS NULL) = (lease_epoch IS NULL)),
    CHECK ((job_id IS NULL) = (probe_source IS NOT NULL)),
    CHECK ((purpose = 'DOCUMENT') = (document_id IS NOT NULL)),
    CHECK (purpose <> 'DOCUMENT' OR job_id IS NOT NULL),
    CHECK ((state = 'SENDING') = (finished_at IS NULL)),
    CHECK (state <> 'SENT' OR http_status IS NOT NULL OR failure_kind IS NOT NULL)
);

CREATE INDEX http_sends_budget ON collector.http_sends (budget_day);
CREATE INDEX http_sends_job_open ON collector.http_sends (job_id) WHERE state = 'SENDING';

-- 전역 동시성 1: 진행 중(SENDING) 송신은 표 전체에 하나, worker와 probe와 두 번째 worker 모두에 적용
CREATE UNIQUE INDEX http_sends_one_in_flight ON collector.http_sends (state) WHERE state = 'SENDING';

-- 일일 예산: 같은 KST 날짜의 송신 수가 이 행의 budget_limit 이상이면 거부
-- BEFORE trigger에서는 생성 열이 아직 계산되지 않으므로 날짜를 직접 계산
CREATE FUNCTION collector.http_sends_budget_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    day date := (NEW.reserved_at AT TIME ZONE 'Asia/Seoul')::date;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('collector.http_sends.budget'));
    IF (SELECT count(*) FROM collector.http_sends WHERE budget_day = day) >= NEW.budget_limit THEN
        RAISE EXCEPTION 'daily send budget % exhausted for %', NEW.budget_limit, day
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER http_sends_budget_guard
    BEFORE INSERT ON collector.http_sends
    FOR EACH ROW EXECUTE FUNCTION collector.http_sends_budget_guard();

-- SENDING에서 SENT 또는 UNKNOWN으로 한 번만, 나머지 열 불변
CREATE FUNCTION collector.http_sends_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- budget_day는 생성 열이라 BEFORE trigger의 NEW에서 아직 NULL, 비교에서 제외
    IF OLD.state <> 'SENDING' OR NEW.state = 'SENDING'
       OR (to_jsonb(NEW) - ARRAY['state', 'http_status', 'failure_kind', 'finished_at', 'budget_day'])
          <> (to_jsonb(OLD) - ARRAY['state', 'http_status', 'failure_kind', 'finished_at', 'budget_day']) THEN
        RAISE EXCEPTION 'send % is finished once and otherwise immutable', OLD.send_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER http_sends_guard
    BEFORE UPDATE ON collector.http_sends
    FOR EACH ROW EXECUTE FUNCTION collector.http_sends_guard();

CREATE TRIGGER http_sends_no_delete
    BEFORE DELETE ON collector.http_sends
    FOR EACH ROW EXECUTE FUNCTION collector.reject_change();

-- host별 간격, 서킷, 429 대기, robots 캐시 (상한 수치는 CrawlPolicy가 관리)
-- 송신 직렬화는 http_sends_one_in_flight가 하고, 이 행의 FOR UPDATE는 host 상태 읽기·쓰기를 묶을 뿐
CREATE TABLE collector.host_states (
    host                 text PRIMARY KEY,
    next_allowed_at      timestamptz NOT NULL DEFAULT '-infinity',
    consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    circuit_level        integer NOT NULL DEFAULT 0 CHECK (circuit_level >= 0),
    circuit_open_until   timestamptz,
    half_open            boolean NOT NULL DEFAULT false,
    throttle_level       integer NOT NULL DEFAULT 0 CHECK (throttle_level >= 0),
    robots_status        integer,
    robots_body          text,
    robots_fetched_at    timestamptz,
    CHECK ((robots_status IS NULL) = (robots_fetched_at IS NULL))
);
