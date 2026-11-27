-- pipeline schema, second migration. Adds the ASCIIDOC_UNRESOLVED hold reason.
-- The column CHECK from V001 has the deterministic PostgreSQL name <table>_<column>_check.

ALTER TABLE pipeline.document_artifacts
    DROP CONSTRAINT document_artifacts_hold_reason_check,
    ADD CONSTRAINT document_artifacts_hold_reason_check CHECK (hold_reason IN (
        'INVALID_UNICODE', 'SELECTOR_UNVERIFIED', 'ROOT_AMBIGUOUS', 'BODY_EMPTY', 'TITLE_MISSING',
        'VERSION_MARKER_MISSING', 'VERSION_MARKER_CONFLICT', 'TAB_DEFAULT_UNRESOLVED',
        'TAB_STRUCTURE_UNSUPPORTED', 'BLOCK_NESTING_UNSUPPORTED', 'CODE_OVER_HARD_LIMIT',
        'ASCIIDOC_UNRESOLVED'));
