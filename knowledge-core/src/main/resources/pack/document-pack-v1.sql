PRAGMA foreign_keys = ON;
PRAGMA application_id = 1397771083;
PRAGMA user_version = 1;
PRAGMA journal_mode = DELETE;
PRAGMA page_size = 4096;
CREATE TABLE pack_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;
CREATE TABLE documents (
    document_id TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    document_type TEXT NOT NULL CHECK(document_type IN ('reference','javadoc','properties','dependencies','guide','migration','release-note','project-overview','example','config-metadata')),
    applicability TEXT NOT NULL CHECK(applicability IN ('EXACT_RELEASE','TRANSITION_RANGE','VERSION_INDEPENDENT')),
    exact_version TEXT,
    source_snapshot_id TEXT NOT NULL,
    raw_sha256 TEXT NOT NULL,
    source_map_json TEXT NOT NULL CHECK(json_valid(source_map_json)),
    CHECK((applicability = 'EXACT_RELEASE') = (exact_version IS NOT NULL))
) WITHOUT ROWID;
CREATE TABLE sections (
    section_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents(document_id),
    parent_section_id TEXT REFERENCES sections(section_id),
    anchor TEXT,
    heading_path TEXT NOT NULL CHECK(json_valid(heading_path)),
    ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
    UNIQUE(document_id, ordinal)
) WITHOUT ROWID;
CREATE TABLE chunks (
    rowid INTEGER PRIMARY KEY,
    chunk_id TEXT NOT NULL UNIQUE,
    document_id TEXT NOT NULL REFERENCES documents(document_id),
    section_id TEXT NOT NULL REFERENCES sections(section_id),
    ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
    content TEXT NOT NULL,
    heading_path TEXT NOT NULL,
    blocks_json TEXT NOT NULL CHECK(json_valid(blocks_json)),
    source_map_json TEXT NOT NULL CHECK(json_valid(source_map_json)),
    UNIQUE(section_id, ordinal)
);
CREATE TABLE code_variants (
    chunk_rowid INTEGER NOT NULL REFERENCES chunks(rowid),
    block_index INTEGER NOT NULL CHECK(block_index >= 0),
    group_type TEXT NOT NULL CHECK(group_type IN ('language','build-tool','gradle-dsl','config-style','other')),
    variant TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
    is_default INTEGER NOT NULL CHECK(is_default IN (0,1)),
    code TEXT NOT NULL,
    source_map_json TEXT NOT NULL CHECK(json_valid(source_map_json)),
    PRIMARY KEY(chunk_rowid, block_index, variant),
    UNIQUE(chunk_rowid, block_index, ordinal)
) WITHOUT ROWID;
CREATE UNIQUE INDEX one_default_variant ON code_variants(chunk_rowid, block_index) WHERE is_default = 1;
CREATE TABLE identifiers (
    identifier TEXT NOT NULL,
    chunk_rowid INTEGER NOT NULL REFERENCES chunks(rowid),
    PRIMARY KEY(identifier, chunk_rowid)
) WITHOUT ROWID;
CREATE TABLE doc_links (
    from_chunk_rowid INTEGER NOT NULL REFERENCES chunks(rowid),
    target_url TEXT NOT NULL,
    target_anchor TEXT NOT NULL DEFAULT '',
    PRIMARY KEY(from_chunk_rowid, target_url, target_anchor)
) WITHOUT ROWID;
CREATE VIRTUAL TABLE chunks_fts USING fts5(
    content, heading_path,
    content='chunks', content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 0'
);
CREATE TABLE chunk_vectors (
    chunk_rowid INTEGER PRIMARY KEY REFERENCES chunks(rowid),
    model_key TEXT NOT NULL,
    dimension INTEGER NOT NULL CHECK(dimension > 0),
    encoding TEXT NOT NULL CHECK(encoding = 'float32-le'),
    embedding BLOB NOT NULL,
    CHECK(length(embedding) = 4 * dimension)
);
CREATE TABLE vector_omissions (
    chunk_rowid INTEGER PRIMARY KEY REFERENCES chunks(rowid),
    reason TEXT NOT NULL CHECK(reason IN ('ATOMIC_BLOCK_OVER_MODEL_LIMIT'))
);
