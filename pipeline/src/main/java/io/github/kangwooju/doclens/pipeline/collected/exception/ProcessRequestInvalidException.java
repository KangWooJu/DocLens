package io.github.kangwooju.doclens.pipeline.collected.exception;

/** 처리 요청 본문이 JSON 객체가 아니거나 형식 또는 지원 범위를 벗어남, 같은 본문으로는 통과하지 않음 */
public class ProcessRequestInvalidException extends RuntimeException {

    /** 어긋난 필드와 내용 */
    public ProcessRequestInvalidException(
            String field,
            String detail
    ) {

        super("processRequest " + field + " is invalid: " + detail);
    }
}
