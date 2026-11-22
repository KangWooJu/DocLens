-- pipeline schema, first migration. Flyway creates the schema and pipeline.flyway_schema_history.

CREATE TABLE pipeline.builds (
    build_id uuid PRIMARY KEY,
    job_key text NOT NULL UNIQUE CHECK (job_key ~ '^sha256:[0-9a-f]{64}$'),
    pack_id text NOT NULL,
    source_attribution text NOT NULL,
    created_by text NOT NULL,
    project text NOT NULL,
    exact_version text NOT NULL,
    applicability text NOT NULL CHECK (applicability = 'EXACT_RELEASE'),
    run_input_snapshot_hash text NOT NULL CHECK (run_input_snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    input_scope_key text NOT NULL CHECK (input_scope_key ~ '^sha256:[0-9a-f]{64}$'),
    input_snapshot_hash text NOT NULL CHECK (input_snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    processing_config_hash text NOT NULL,
    processing_config_json text NOT NULL,
    model_checksum text CHECK (model_checksum IS NULL OR model_checksum ~ '^sha256:[0-9a-f]{64}$'),
    parser_policy_version text NOT NULL,
    chunker_policy_version text NOT NULL,
    source_digest text NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    document_count integer NOT NULL CHECK (document_count BETWEEN 1 AND 100000),
    status text NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'NEEDS_REVIEW', 'FAILED', 'SEALED', 'SUCCEEDED')),
    failure_reason text,
    pack_object_key text CHECK (pack_object_key ~ '^packs/[A-Za-z0-9@._/-]+$'
        AND pack_object_key !~ '(^|/)\.{1,2}(/|$)'),
    pack_checksum text CHECK (pack_checksum ~ '^sha256:[0-9a-f]{64}$'),
    pack_size_bytes bigint CHECK (pack_size_bytes BETWEEN 1 AND 536870912),
    pack_logical_sha256 text CHECK (pack_logical_sha256 ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT builds_pack_id CHECK (pack_id = project || '-' || exact_version),
    CONSTRAINT builds_config_hash CHECK (
        processing_config_hash = 'sha256:' || encode(sha256(convert_to(processing_config_json, 'UTF8')), 'hex')
    ),
    CONSTRAINT builds_failure_reason CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT builds_pack_columns CHECK (
        num_nonnulls(pack_object_key, pack_checksum, pack_size_bytes, pack_logical_sha256)
            = CASE WHEN status = 'SUCCEEDED' THEN 4 ELSE 0 END
    )
);

-- Expected sources, inserted once when the Build starts; basis for sealing and Pack metadata
CREATE TABLE pipeline.build_sources (
    build_id uuid NOT NULL REFERENCES pipeline.builds(build_id),
    document_id text COLLATE "C" NOT NULL CHECK (document_id ~ '^sha256:[0-9a-f]{64}$'),
    document_type text NOT NULL CHECK (document_type IN (
        'REFERENCE', 'JAVADOC', 'PROPERTIES', 'DEPENDENCIES', 'GUIDE',
        'MIGRATION', 'RELEASE_NOTE', 'PROJECT_OVERVIEW', 'EXAMPLE', 'CONFIG_METADATA')),
    canonical_url text NOT NULL,
    original_url text NOT NULL,
    final_url text NOT NULL,
    source_snapshot_id text COLLATE "C" NOT NULL,
    raw_sha256 text NOT NULL CHECK (raw_sha256 ~ '^sha256:[0-9a-f]{64}$'),
    raw_object_key text NOT NULL
        CHECK (raw_object_key ~ '^raw/[a-zA-Z0-9_@.-]+(/[a-zA-Z0-9_@.-]+)*$'
            AND raw_object_key !~ '(^|/)\.{1,2}(/|$)'),
    PRIMARY KEY (build_id, document_id)
);

-- PARSE and CHUNK artifacts of one document as byte-exact canonical JSON text, hashes checked by the database
CREATE TABLE pipeline.document_artifacts (
    build_id uuid NOT NULL,
    document_id text COLLATE "C" NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('DONE', 'HELD')),
    hold_reason text CHECK (hold_reason IN (
        'INVALID_UNICODE', 'SELECTOR_UNVERIFIED', 'ROOT_AMBIGUOUS', 'BODY_EMPTY', 'TITLE_MISSING',
        'VERSION_MARKER_MISSING', 'VERSION_MARKER_CONFLICT', 'TAB_DEFAULT_UNRESOLVED',
        'TAB_STRUCTURE_UNSUPPORTED', 'BLOCK_NESTING_UNSUPPORTED', 'CODE_OVER_HARD_LIMIT')),
    structured_json text,
    structured_sha256 text,
    pack_document_json text,
    pack_document_sha256 text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (build_id, document_id),
    FOREIGN KEY (build_id, document_id)
        REFERENCES pipeline.build_sources(build_id, document_id),
    CONSTRAINT artifacts_outcome_shape CHECK (
        (outcome = 'DONE'
            AND hold_reason IS NULL
            AND structured_json IS NOT NULL
            AND structured_sha256 IS NOT NULL
            AND pack_document_json IS NOT NULL
            AND pack_document_sha256 IS NOT NULL)
        OR
        (outcome = 'HELD'
            AND hold_reason IS NOT NULL
            AND structured_json IS NULL
            AND structured_sha256 IS NULL
            AND pack_document_json IS NULL
            AND pack_document_sha256 IS NULL)
    ),
    CONSTRAINT artifacts_structured_hash CHECK (
        structured_json IS NULL
        OR structured_sha256 = 'sha256:' || encode(sha256(convert_to(structured_json, 'UTF8')), 'hex')
    ),
    CONSTRAINT artifacts_pack_document_hash CHECK (
        pack_document_json IS NULL
        OR pack_document_sha256 = 'sha256:' || encode(sha256(convert_to(pack_document_json, 'UTF8')), 'hex')
    )
);

-- Reduced PG03 verified_stage_results: a row means the stage is verified
CREATE TABLE pipeline.verified_stage_results (
    build_id uuid NOT NULL REFERENCES pipeline.builds(build_id),
    stage text NOT NULL CHECK (stage IN ('PARSE', 'CHUNK', 'EMBED', 'EXPORT', 'VERIFY')),
    output_digest text NOT NULL CHECK (output_digest ~ '^sha256:[0-9a-f]{64}$'),
    item_count integer NOT NULL CHECK (item_count >= 0),
    byte_count bigint NOT NULL CHECK (byte_count >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (build_id, stage)
);

-- The three artifact tables are append-only; retention cleanup belongs to Phase 5 maintenance
CREATE FUNCTION pipeline.reject_row_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'pipeline.% is append-only', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER build_sources_append_only
    BEFORE UPDATE OR DELETE ON pipeline.build_sources
    FOR EACH ROW EXECUTE FUNCTION pipeline.reject_row_change();

CREATE TRIGGER document_artifacts_append_only
    BEFORE UPDATE OR DELETE ON pipeline.document_artifacts
    FOR EACH ROW EXECUTE FUNCTION pipeline.reject_row_change();

CREATE TRIGGER verified_stage_results_append_only
    BEFORE UPDATE OR DELETE ON pipeline.verified_stage_results
    FOR EACH ROW EXECUTE FUNCTION pipeline.reject_row_change();

-- builds: created as RUNNING only, identity columns immutable, allowed transitions only, never deleted
CREATE FUNCTION pipeline.guard_build_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RUNNING' THEN
            RAISE EXCEPTION 'pipeline.builds rows start as RUNNING'
                USING ERRCODE = 'restrict_violation';
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'pipeline.builds rows are never deleted'
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF (NEW.build_id, NEW.job_key, NEW.pack_id, NEW.source_attribution, NEW.created_by,
        NEW.project, NEW.exact_version, NEW.applicability, NEW.run_input_snapshot_hash,
        NEW.input_scope_key, NEW.input_snapshot_hash, NEW.processing_config_hash,
        NEW.processing_config_json, NEW.model_checksum, NEW.parser_policy_version,
        NEW.chunker_policy_version, NEW.source_digest, NEW.document_count, NEW.created_at)
       IS DISTINCT FROM
       (OLD.build_id, OLD.job_key, OLD.pack_id, OLD.source_attribution, OLD.created_by,
        OLD.project, OLD.exact_version, OLD.applicability, OLD.run_input_snapshot_hash,
        OLD.input_scope_key, OLD.input_snapshot_hash, OLD.processing_config_hash,
        OLD.processing_config_json, OLD.model_checksum, OLD.parser_policy_version,
        OLD.chunker_policy_version, OLD.source_digest, OLD.document_count, OLD.created_at) THEN
        RAISE EXCEPTION 'pipeline.builds identity columns are immutable'
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF NOT (
        (OLD.status = 'RUNNING' AND NEW.status IN ('SEALED', 'NEEDS_REVIEW', 'FAILED'))
        OR (OLD.status = 'SEALED' AND NEW.status = 'SUCCEEDED')
    ) THEN
        RAISE EXCEPTION 'pipeline.builds transition % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER builds_guard
    BEFORE INSERT OR UPDATE OR DELETE ON pipeline.builds
    FOR EACH ROW EXECUTE FUNCTION pipeline.guard_build_change();
