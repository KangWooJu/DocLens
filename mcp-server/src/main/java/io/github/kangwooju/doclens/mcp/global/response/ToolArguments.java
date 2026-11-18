package io.github.kangwooju.doclens.mcp.global.response;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/**
 * Tool 입력 인자 검사(닫힌 입력, 정수 범위)
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

    private ToolArguments() {
    }

    /** 허용 목록 밖 인자 거절 */
    public static void requireOnly(
            Map<String, Object> arguments,
            Set<String> allowed
    ) {

        for (String name : arguments
                .keySet()) {
            if (!allowed
                    .contains(name)) {
                throw new InvalidToolInputException("unknown argument");
            }
        }
    }

    /** maxContextTokens 값, 없으면 기본값 */
    public static int maxContextTokens(Map<String, Object> arguments) {

        Object value = arguments
                .get(MAX_CONTEXT_TOKENS);

        if (value == null) {
            return DEFAULT_BUDGET;
        }

        long budget = integerValue(value);

        if (budget < MIN_BUDGET || budget > MAX_BUDGET) {
            throw new InvalidToolInputException(MAX_CONTEXT_TOKENS + " out of range");
        }

        return (int) budget;
    }

    // JSON 정수 값의 long 변환, 소수·문자열·범위 밖 정수 거절
    private static long integerValue(Object value) {

        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value)
                    .longValue();
        }

        if (value instanceof BigInteger big && big
                .bitLength() < 63) {
            return big
                    .longValue();
        }

        throw new InvalidToolInputException(MAX_CONTEXT_TOKENS + " must be an integer");
    }
}
