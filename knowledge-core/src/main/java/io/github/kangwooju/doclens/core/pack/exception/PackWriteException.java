package io.github.kangwooju.doclens.core.pack.exception;

/** Pack 입력 거절 또는 파일 완결 실패 */
public class PackWriteException extends RuntimeException {

    /** 입력 거절 */
    public PackWriteException(String message) {

        super(message);
    }

    /** 원인 예외가 있는 거절 */
    public PackWriteException(
            String message,
            Throwable cause
    ) {

        super(
                message,
                cause
        );
    }
}
