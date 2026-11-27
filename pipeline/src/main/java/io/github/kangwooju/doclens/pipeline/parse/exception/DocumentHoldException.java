package io.github.kangwooju.doclens.pipeline.parse.exception;

import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

/** 검토가 필요해 이번 Build에서 빼는 문서, 같은 입력이면 같은 이유 */
public class DocumentHoldException extends RuntimeException {

    private final HoldReason reason;

    /** 보류 이유와 설명 */
    public DocumentHoldException(
            HoldReason reason,
            String detail
    ) {

        super(reason + ": " + detail);
        this.reason = reason;
    }

    /** 보류 이유 */
    public HoldReason reason() {

        return reason;
    }
}
