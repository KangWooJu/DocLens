package io.github.kangwooju.doclens.core.pack;

import java.util.regex.Pattern;

/** Pack 입력 record의 생성 시 검사. */
final class Require {

    private static final Pattern SHA256_ID = Pattern.compile("sha256:[0-9a-f]{64}");

    private Require() {
    }

    static void present(
            Object value,
            String name
    ) {

        if (value == null) {
            throw new PackWriteException(name + " is required");
        }
    }

    static void text(
            String value,
            String name
    ) {

        if (value == null || value.isBlank()) {
            throw new PackWriteException(name + " must not be blank");
        }
    }

    static void sha256Id(
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

    static void nonNegative(
            int value,
            String name
    ) {

        if (value < 0) {
            throw new PackWriteException(name + " must not be negative: " + value);
        }
    }
}
