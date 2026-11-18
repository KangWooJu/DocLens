package io.github.kangwooju.doclens.mcp.global.response;

/**
 * 응답 예산 보고(D16)
 *
 * <p>wireBytes는 LF를 포함한 JSON-RPC 응답 한 줄 전체의 UTF-8 바이트,
 * estimatedTokens는 ceil(wireBytes / 3)
 * <p>actualTokens는 서버가 알 수 없는 값이라 항상 null
 */
public record Budget(
        int requested,
        long estimatedTokens,
        long wireBytes,
        String estimator,
        Object actualTokens
) {

    /** 추정 방식 이름 */
    public static final String ESTIMATOR = "utf8-div3-v1";

    /** 측정 전 초기값 */
    public static Budget initial(int requested) {

        return new Budget(
                requested,
                1,
                1,
                ESTIMATOR,
                null
        );
    }

    /** 측정한 wire 바이트 반영 */
    public Budget measured(long bytes) {

        return new Budget(
                requested,
                (bytes + 2) / 3,
                bytes,
                ESTIMATOR,
                null
        );
    }

    /** 요청 예산 안 여부(추정 토큰 ≤ 예산, wire 바이트 ≤ 예산 × 4) */
    public boolean fitsRequested() {

        return estimatedTokens <= requested && wireBytes <= 4L * requested;
    }
}
