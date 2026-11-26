package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 보류 문서가 있어 NEEDS_REVIEW로 끝난 Build, 사람 확인 필요 */
public class BuildNeedsReviewException extends RuntimeException {

    /** Build와 보류 문서 수 */
    public BuildNeedsReviewException(
            String buildId,
            int holdCount
    ) {

        super("build " + buildId + " needs review: " + holdCount + " documents are held");
    }
}
