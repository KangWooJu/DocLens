package io.github.kangwooju.doclens.mcp.global.response.exception;

import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;

/** Tool 입력 오류, INVALID_INPUT으로 응답 */
public class InvalidToolInputException extends ToolFailureException {

    /** 입력 오류 설명 */
    public InvalidToolInputException(String message) {

        super(
                ToolErrorCode.INVALID_INPUT,
                message
        );
    }
}
