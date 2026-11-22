package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 같은 문서에 이미 저장한 산출물과 다른 산출물, 처음 행은 그대로 두고 사람 확인 */
public class StageNondeterminismException extends RuntimeException {

    /** Build와 문서 */
    public StageNondeterminismException(
            String buildId,
            String documentId
    ) {

        super("artifact of " + documentId + " in build " + buildId + " differs from the stored one");
    }
}
