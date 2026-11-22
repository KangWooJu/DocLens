package io.github.kangwooju.doclens.pipeline.pack.type;

/** Build 상태, RUNNING에서 시작해 SEALED를 거쳐 SUCCEEDED, NEEDS_REVIEW와 FAILED는 끝 상태 */
public enum BuildStatus {
    RUNNING,
    NEEDS_REVIEW,
    FAILED,
    SEALED,
    SUCCEEDED
}
