package io.github.kangwooju.doclens.mcp.global.response.exception;

import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;

/** 오류 코드로 응답하는 Tool 처리 실패 */
public class ToolFailureException extends RuntimeException {

    private final ToolErrorCode code;

    /** 오류 코드 이름을 메시지로 쓰는 실패 */
    public ToolFailureException(ToolErrorCode code) {

        this(
                code,
                code
                        .name()
        );
    }

    /** 오류 코드와 로그용 메시지 */
    public ToolFailureException(
            ToolErrorCode code,
            String message
    ) {

        super(message);
        this.code = code;
    }

    /** 응답 오류 코드 */
    public ToolErrorCode code() {

        return code;
    }
}
