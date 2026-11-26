package io.github.kangwooju.doclens.scheduler.dispatch.type;

/** 실패 원인 종류, 확정 거절과 collector job 종결 실패와 무결성 불일치 */
public enum FailureKind {

    REJECTED,
    REMOTE_TERMINAL,
    INTEGRITY
}
