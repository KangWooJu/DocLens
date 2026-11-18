package io.github.kangwooju.doclens.mcp.global.response;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/**
 * Tool 입력 인자 검사(닫힌 입력, 정수 범위, 문자열 길이)
 */
public final class ToolArguments {

    /** maxContextTokens 최솟값 */
    public static final int MIN_BUDGET = 256;

    /** maxContextTokens 최댓값 */
    public static final int MAX_BUDGET = 16384;

    /** maxContextTokens 기본값 */
    public static final int DEFAULT_BUDGET = 4096;

    /** 예산 인자 이름 */
    public static final String MAX_CONTEXT_TOKENS = "maxContextTokens";

    /** 목록 개수 인자 이름 */
    public static final String LIMIT = "limit";

    /** limit 최솟값 */
    public static final int MIN_LIMIT = 1;

    /** limit 최댓값 */
    public static final int MAX_LIMIT = 50;

    /** limit 기본값 */
    public static final int DEFAULT_LIMIT = 20;

    private ToolArguments() {
    }

    /** 허용 목록 밖 인자 거절, 인자 없음은 빈 Map */
    public static Map<String, Object> requireOnly(
            Map<String, Object> arguments,
            Set<String> allowed
    ) {

        if (arguments == null) {
            return Map
                    .of();
        }

        for (String name : arguments
                .keySet()) {
            if (!allowed
                    .contains(name)) {
                throw new InvalidToolInputException("unknown argument");
            }
        }

        return arguments;
    }

    /** maxContextTokens 값, 없으면 기본값 */
    public static int maxContextTokens(Map<String, Object> arguments) {

        return integer(
                arguments,
                MAX_CONTEXT_TOKENS,
                MIN_BUDGET,
                MAX_BUDGET,
                DEFAULT_BUDGET
        );
    }

    /** limit 값, 없으면 기본값 */
    public static int limit(Map<String, Object> arguments) {

        return integer(
                arguments,
                LIMIT,
                MIN_LIMIT,
                MAX_LIMIT,
                DEFAULT_LIMIT
        );
    }

    /** 범위 안 정수 인자, 없으면 기본값 */
    public static int integer(
            Map<String, Object> arguments,
            String name,
            int min,
            int max,
            int defaultValue
    ) {

        Object value = arguments
                .get(name);

        if (value == null) {
            return defaultValue;
        }

        long number = integerValue(
                value,
                name
        );

        if (number < min || number > max) {
            throw new InvalidToolInputException(name + " out of range");
        }

        return (int) number;
    }

    /** 필수 문자열 인자(1자 이상, 최대 code point 수 제한) */
    public static String text(
            Map<String, Object> arguments,
            String name,
            int maxLength
    ) {

        String value = optionalText(
                arguments,
                name,
                maxLength
        );

        if (value == null) {
            throw new InvalidToolInputException(name + " is required");
        }

        return value;
    }

    /** 선택 문자열 인자, 없으면 null */
    public static String optionalText(
            Map<String, Object> arguments,
            String name,
            int maxLength
    ) {

        Object value = arguments
                .get(name);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String text)) {
            throw new InvalidToolInputException(name + " must be a string");
        }

        int length = text
                .codePointCount(
                        0,
                        text
                                .length()
                );

        if (length < 1 || length > maxLength) {
            throw new InvalidToolInputException(name + " length out of range");
        }

        return text;
    }

    // JSON 정수 값의 long 변환, 소수·문자열·범위 밖 정수 거절
    private static long integerValue(
            Object value,
            String name
    ) {

        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value)
                    .longValue();
        }

        if (value instanceof BigInteger big && big
                .bitLength() < 63) {
            return big
                    .longValue();
        }

        throw new InvalidToolInputException(name + " must be an integer");
    }
}
