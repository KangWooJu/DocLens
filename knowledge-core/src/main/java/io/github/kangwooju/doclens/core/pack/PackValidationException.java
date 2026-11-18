package io.github.kangwooju.doclens.core.pack;

/** Pack 열기 또는 검증 실패, reason으로 원인 종류 구별 */
public class PackValidationException extends RuntimeException {

    /** 거절 원인 */
    public enum Reason {
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

    private final Reason reason;

    public PackValidationException(
            Reason reason,
            String message
    ) {

        super(reason + ": " + message);
        this.reason = reason;
    }

    public PackValidationException(
            Reason reason,
            String message,
            Throwable cause
    ) {

        super(
                reason + ": " + message,
                cause
        );
        this.reason = reason;
    }

    public Reason reason() {

        return reason;
    }
}
