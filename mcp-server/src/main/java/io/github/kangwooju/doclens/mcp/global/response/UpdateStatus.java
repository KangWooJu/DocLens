package io.github.kangwooju.doclens.mcp.global.response;

/**
 * Pack 갱신 상태(D16)
 */
public enum UpdateStatus {

    CURRENT,
    STALE_SOURCE,
    UPDATE_FAILED,
    FIRST_INSTALL_PENDING
}
