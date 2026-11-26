-- scheduler schema, first migration. One row per collection run: run state, delivery state and peer-reported state in separate columns
CREATE TABLE scheduler.runs (
    run_id uuid PRIMARY KEY,
    schedule_key text NOT NULL,
    slot_key text NOT NULL CHECK (slot_key ~ '^(daily:[0-9]{4}-[0-9]{2}-[0-9]{2}|manual:[A-Za-z0-9._-]{1,64})$'),
    state text NOT NULL DEFAULT 'COLLECTING'
        CHECK (state IN ('COLLECTING', 'PROCESSING', 'DONE', 'FAILED')),
    delivery_state text NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_state IN ('PENDING', 'SENT')),
    remote_state text,
    remote_checked_at timestamptz,
    failed_stage text CHECK (failed_stage IN ('COLLECTING', 'PROCESSING')),
    failure_kind text CHECK (failure_kind IN ('REJECTED', 'REMOTE_TERMINAL', 'INTEGRITY')),
    collection_request text NOT NULL,
    collector_job_id uuid,
    ready_run_ref jsonb,
    process_request text,
    result jsonb,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (schedule_key, slot_key),
    CHECK ((delivery_state = 'SENT') <= (collector_job_id IS NOT NULL)),
    CHECK ((state = 'PROCESSING') <= (ready_run_ref IS NOT NULL)),
    CHECK ((state = 'DONE') = (result IS NOT NULL)),
    CHECK ((state = 'FAILED') = (failed_stage IS NOT NULL)),
    CHECK ((failed_stage IS NULL) = (failure_kind IS NULL))
);

-- at most one active run per schedule, a later slot waits (coalesce)
CREATE UNIQUE INDEX runs_one_active
    ON scheduler.runs (schedule_key)
    WHERE state IN ('COLLECTING', 'PROCESSING');
