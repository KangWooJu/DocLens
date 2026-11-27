package io.github.kangwooju.doclens.pipeline.collected.exception;

import java.util.List;

/** 필수 문서 수집 실패가 있는 projection, Build 없이 입력 보류 */
public class CollectedRunIncompleteException extends RuntimeException {

    private final List<String> documentIds;

    /** 실패한 필수 문서 ID와 오류 code, 두 목록은 같은 순서 */
    public CollectedRunIncompleteException(
            List<String> documentIds,
            List<String> errorCodes
    ) {

        super("required documents failed: " + documentIds + " " + errorCodes);
        this.documentIds = List
                .copyOf(documentIds);
    }

    /** 실패한 필수 문서 ID, documentId 순 */
    public List<String> documentIds() {

        return documentIds;
    }
}
