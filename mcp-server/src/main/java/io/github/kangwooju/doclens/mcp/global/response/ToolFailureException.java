package io.github.kangwooju.doclens.mcp.global.response;

/**
 * 닫힌 오류 코드 하나로 응답하는 Tool 처리 실패, 메시지는 로그·응답에 내지 않음
 */
public class ToolFailureException extends RuntimeException {

    private final ToolErrorCode code;

    public ToolFailureException(ToolErrorCode code) {

        this(
                code,
                code
                        .name()
        );
    }

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
