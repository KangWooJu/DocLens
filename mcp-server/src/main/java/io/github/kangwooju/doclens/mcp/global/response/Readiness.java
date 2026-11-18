package io.github.kangwooju.doclens.mcp.global.response;

/**
 * 서버 준비 상태(D16), 프로토콜 연결 상태와 별개
 */
public enum Readiness {

    STARTING,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    READY,
    READY_STALE,
    NOT_READY
}
