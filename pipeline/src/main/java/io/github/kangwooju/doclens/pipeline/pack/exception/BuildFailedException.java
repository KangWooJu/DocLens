package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 원본 bytes가 수집 기록과 달라 FAILED로 끝난 Build, 같은 입력으로는 다시 만들지 않음 */
public class BuildFailedException extends RuntimeException {

    /** Build와 실패 이유 */
    public BuildFailedException(
            String buildId,
            String failureReason
    ) {

        super("build " + buildId + " failed: " + failureReason);
    }
}
