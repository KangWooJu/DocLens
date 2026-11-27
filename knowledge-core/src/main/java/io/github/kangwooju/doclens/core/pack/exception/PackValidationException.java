package io.github.kangwooju.doclens.core.pack.exception;

import io.github.kangwooju.doclens.core.pack.type.PackValidationReason;

/** Pack 열기 또는 검증 실패, reason으로 원인 구별 */
public class PackValidationException extends RuntimeException {

    private final PackValidationReason reason;

    /** 거절 원인과 설명 */
    public PackValidationException(
            PackValidationReason reason,
            String message
    ) {

        super(reason + ": " + message);
        this.reason = reason;
    }

    /** 원인 예외가 있는 거절 */
    public PackValidationException(
            PackValidationReason reason,
            String message,
            Throwable cause
    ) {

        super(
                reason + ": " + message,
                cause
        );
        this.reason = reason;
    }

    /** 거절 원인 */
    public PackValidationReason reason() {

        return reason;
    }
}
