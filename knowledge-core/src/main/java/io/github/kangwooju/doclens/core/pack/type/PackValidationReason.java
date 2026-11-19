package io.github.kangwooju.doclens.core.pack.type;

/** Pack 열기, 검증 거절 원인 */
public enum PackValidationReason {
    NOT_A_FILE,
    NOT_A_PACK,
    UNSUPPORTED_SCHEMA_VERSION,
    UNSUPPORTED_READER_VERSION,
    INVALID_META,
    CORRUPT,
    INCONSISTENT_CONTENT,
    SEARCH_INDEX_BROKEN,
    LOGICAL_HASH_MISMATCH
}
