package io.github.kangwooju.doclens.core.json;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical JSON 직렬화
 * 키는 code point 순 정렬, 공백 없음, 문자열은 NFC, 숫자는 정수만 허용
 */
public final class CanonicalJson {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    // 정적 메서드 전용
    private CanonicalJson() {
    }

    /** JSON 텍스트를 읽어 canonical 형태로 다시 쓴 결과 */
    public static String canonicalize(String json) {

        try {
            return write(JSON_MAPPER
                    .readValue(
                            json,
                            Object.class
                    ));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "invalid JSON: " + exception
                            .getOriginalMessage(),
                    exception
            );
        }
    }

    /** Java 값(Map, List, String, 정수, Boolean, null)의 canonical JSON */
    public static String write(Object value) {

        StringBuilder out = new StringBuilder();
        append(
                out,
                value
        );

        return out
                .toString();
    }

    /** canonical JSON UTF-8 바이트의 SHA-256(`sha256:` 접두어) */
    public static String sha256(String canonical) {

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(canonical
                            .getBytes(StandardCharsets.UTF_8));

            return "sha256:" + HexFormat
                    .of()
                    .formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // 값 종류별 canonical JSON 출력 분기
    private static void append(
            StringBuilder out,
            Object value
    ) {

        switch (value) {
            case null -> out
                    .append("null");
            case Boolean bool -> out
                    .append(bool);
            case Integer number -> out
                    .append(number);
            case Long number -> out
                    .append(number);
            case BigInteger number -> out
                    .append(number);
            case String text -> appendString(
                    out,
                    Normalizer
                            .normalize(
                                    text,
                                    Normalizer.Form.NFC
                            )
            );
            case Map<?, ?> map -> appendObject(
                    out,
                    map
            );
            case List<?> list -> appendArray(
                    out,
                    list
            );
            default -> throw new IllegalArgumentException(
                    "unsupported JSON value: " + value
                            .getClass()
                            .getName()
            );
        }
    }

    // code point 순서로 정렬한 키의 객체 출력
    private static void appendObject(
            StringBuilder out,
            Map<?, ?> map
    ) {

        List<String> keys = new ArrayList<>();

        for (Object key : map
                .keySet()) {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }

            keys
                    .add(text);
        }

        // Python sort_keys와 같은 code point 순서(UTF-16 compareTo와 다른 순서)
        keys
                .sort(CanonicalJson::compareCodePoints);

        out
                .append('{');

        int keyCount = keys
                .size();

        for (int index = 0; index < keyCount; index++) {
            if (index > 0) {
                out
                        .append(',');
            }

            String key = keys
                    .get(index);
            appendString(
                    out,
                    key
            );
            out
                    .append(':');
            append(
                    out,
                    map
                            .get(key)
            );
        }

        out
                .append('}');
    }

    // 배열 요소의 순서 그대로 출력
    private static void appendArray(
            StringBuilder out,
            List<?> list
    ) {

        out
                .append('[');

        int elementCount = list
                .size();

        for (int index = 0; index < elementCount; index++) {
            if (index > 0) {
                out
                        .append(',');
            }

            append(
                    out,
                    list
                            .get(index)
            );
        }

        out
                .append(']');
    }

    // 최소 escape 문자열 출력, 짝 없는 surrogate 거절
    private static void appendString(
            StringBuilder out,
            String text
    ) {

        out
                .append('"');

        int length = text
                .length();

        for (int index = 0; index < length; index++) {
            char ch = text
                    .charAt(index);

            if (Character
                    .isSurrogate(ch)) {
                if (!Character
                        .isHighSurrogate(ch)
                        || index + 1 >= length
                        || !Character
                                .isLowSurrogate(text
                                        .charAt(index + 1))) {
                    throw new IllegalArgumentException("unpaired surrogate in JSON string");
                }

                out
                        .append(ch)
                        .append(text
                                .charAt(index + 1));
                index++;
                continue;
            }

            switch (ch) {
                case '"' -> out
                        .append("\\\"");
                case '\\' -> out
                        .append("\\\\");
                case '\n' -> out
                        .append("\\n");
                case '\r' -> out
                        .append("\\r");
                case '\t' -> out
                        .append("\\t");
                case '\b' -> out
                        .append("\\b");
                case '\f' -> out
                        .append("\\f");
                default -> {
                    if (ch < 0x20) {
                        out
                                .append(String
                                        .format(
                                                "\\u%04x",
                                                (int) ch
                                        ));
                    } else {
                        out
                                .append(ch);
                    }
                }
            }
        }

        out
                .append('"');
    }

    /** Unicode code point 순서 비교(SQLite BINARY, UTF-8 바이트 순서와 동일) */
    public static int compareCodePoints(
            String left,
            String right
    ) {

        int leftIndex = 0;
        int rightIndex = 0;
        int leftLength = left
                .length();
        int rightLength = right
                .length();

        while (leftIndex < leftLength && rightIndex < rightLength) {
            int leftPoint = left
                    .codePointAt(leftIndex);
            int rightPoint = right
                    .codePointAt(rightIndex);

            if (leftPoint != rightPoint) {
                return Integer
                        .compare(
                                leftPoint,
                                rightPoint
                        );
            }

            leftIndex += Character
                    .charCount(leftPoint);
            rightIndex += Character
                    .charCount(rightPoint);
        }

        return Integer
                .compare(
                        leftLength - leftIndex,
                        rightLength - rightIndex
                );
    }
}
