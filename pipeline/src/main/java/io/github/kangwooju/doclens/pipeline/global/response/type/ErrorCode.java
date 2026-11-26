package io.github.kangwooju.doclens.pipeline.global.response.type;

/** pipeline이 내는 contracts error code */
public enum ErrorCode {
    VALIDATION_FAILED,
    NOT_FOUND,
    AUTH_REQUIRED,
    FORBIDDEN,
    STALE_OWNER,
    ID_INPUT_CONFLICT,
    INVALID_TRANSITION,
    INPUT_INCOMPLETE,
    HASH_MISMATCH,
    NEEDS_REVIEW,
    STORAGE_FAILED,
    INTERNAL_ERROR
}
