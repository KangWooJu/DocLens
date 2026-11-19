package io.github.kangwooju.doclens.core.pack.type;

/** pack_meta 테이블의 키 */
public enum PackMetaKey {

    PACK_ID("packId"),
    PACK_TYPE("packType"),
    PROJECT("project"),
    DOC_VERSION("docVersion"),
    BUILD_ID("buildId"),
    PACK_SCHEMA_VERSION("packSchemaVersion"),
    MIN_READER_VERSION("minReaderVersion"),
    INPUT_SNAPSHOT_HASH("inputSnapshotHash"),
    PROCESSING_CONFIG_HASH("processingConfigHash"),
    PARSER_POLICY_VERSION("parserPolicyVersion"),
    CHUNKER_POLICY_VERSION("chunkerPolicyVersion"),
    SOURCE_ATTRIBUTION("sourceAttribution"),
    SOURCE_SNAPSHOT_IDS("sourceSnapshotIds"),
    LOGICAL_CONTENT_SHA256("logicalContentSha256"),
    CAPABILITIES("capabilities"),
    EMBEDDING("embedding"),
    CREATED_BY("createdBy");

    private final String key;

    // 테이블에 저장하는 키 지정
    PackMetaKey(String key) {

        this.key = key;
    }

    /** 테이블에 저장하는 키 */
    public String key() {

        return key;
    }
}
