package io.github.kangwooju.doclens.core.pack.support;

import java.util.regex.Pattern;

import io.github.kangwooju.doclens.core.pack.exception.PackWriteException;

/** Pack 입력 record의 생성 시 검사 */
public final class Require {

    private static final Pattern SHA256_ID = Pattern
            .compile("sha256:[0-9a-f]{64}");

    // 정적 메서드 전용
    private Require() {
    }

    /** null 값 거절 */
    public static void present(
            Object value,
            String name
    ) {

        if (value == null) {
            throw new PackWriteException(name + " is required");
        }
    }

    /** null이나 공백 문자열 거절 */
    public static void text(
            String value,
            String name
    ) {

        if (value == null
                || value
                        .isBlank()) {
            throw new PackWriteException(name + " must not be blank");
        }
    }

    /** `sha256:` 64자리 소문자 hex 형식이 아닌 값 거절 */
    public static void sha256Id(
            String value,
            String name
    ) {

        if (value == null
                || !SHA256_ID
                        .matcher(value)
                        .matches()) {
            throw new PackWriteException(name + " must be sha256:<64 lowercase hex>: " + value);
        }
    }

    /** 음수 거절 */
    public static void nonNegative(
            int value,
            String name
    ) {

        if (value < 0) {
            throw new PackWriteException(name + " must not be negative: " + value);
        }
    }
}
