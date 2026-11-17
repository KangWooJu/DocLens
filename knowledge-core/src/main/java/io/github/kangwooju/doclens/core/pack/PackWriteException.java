package io.github.kangwooju.doclens.core.pack;

/** Pack 입력 거절 또는 파일 완결 실패. */
public class PackWriteException extends RuntimeException {

    public PackWriteException(String message) {

        super(message);
    }

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
