package io.github.kangwooju.doclens.mcp.global.response.type;

/** 서버 준비 상태 */
public enum Readiness {

    STARTING,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    READY,
    READY_STALE,
    NOT_READY
}
