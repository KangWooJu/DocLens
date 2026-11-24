package io.github.kangwooju.doclens.pipeline.parse.type;

/** 문서 버전 표식을 읽는 위치, COMMIT_PINNED_URL은 문서 URL의 commit이 버전 근거라 표식을 읽지 않음 */
public enum VersionMarkerRule {
    ANTORA_META_VERSION,
    COMMIT_PINNED_URL
}
