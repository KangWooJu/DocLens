package io.github.kangwooju.doclens.mcp.global.response;

/**
 * Tool 오류 코드의 닫힌 목록(D16)
 */
public enum ToolErrorCode {

    BUDGET_TOO_SMALL,
    INVALID_INPUT,
    NOT_READY,
    NOT_FOUND,
    CURSOR_EXPIRED,
    CURSOR_MISMATCH,
    PACK_INCOMPATIBLE,
    MODEL_NOT_READY,
    MODEL_MISMATCH,
    SELFTEST_FAILED,
    QUERY_TOO_LONG,
    INTERNAL_ERROR
}
