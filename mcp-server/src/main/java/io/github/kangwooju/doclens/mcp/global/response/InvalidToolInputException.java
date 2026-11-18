package io.github.kangwooju.doclens.mcp.global.response;

/**
 * Tool 입력 계약 위반, 응답은 INVALID_INPUT 코드만 반환
 */
public class InvalidToolInputException extends RuntimeException {

    public InvalidToolInputException(String message) {

        super(message);
    }
}
