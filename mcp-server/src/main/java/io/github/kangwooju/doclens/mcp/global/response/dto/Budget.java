package io.github.kangwooju.doclens.mcp.global.response.dto;

/** 응답 예산 정보, estimatedTokens는 wireBytes / 3 올림 */
public record Budget(
        int requested,
        long estimatedTokens,
        long wireBytes,
        String estimator,
        Object actualTokens
) {

    /** 요청 예산 최솟값 */
    public static final int MIN = 256;

    /** 요청 예산 최댓값 */
    public static final int MAX = 16384;

    /** 요청 예산 기본값 */
    public static final int DEFAULT = 4096;

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

    /** 요청 예산 안에 들어가는지 여부 */
    public boolean fitsRequested() {

        return estimatedTokens <= requested && wireBytes <= 4L * requested;
    }
}
