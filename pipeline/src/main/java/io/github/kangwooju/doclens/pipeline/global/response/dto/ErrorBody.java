package io.github.kangwooju.doclens.pipeline.global.response.dto;

import java.util.UUID;

import io.github.kangwooju.doclens.pipeline.global.response.type.ErrorCode;

/** contracts error 본문, 요청 ID는 알 수 없어 null, traceId는 응답마다 새 UUID */
public record ErrorBody(
        int schemaVersion,
        ErrorCode code,
        String message,
        String requestId,
        boolean retryable,
        String traceId
) {

    // contracts message 최대 길이
    private static final int MAX_MESSAGE = 500;

    /** code와 message, 재시도 여부의 본문, message는 비면 code 이름, 길면 앞부분만 */
    public static ErrorBody of(
            ErrorCode code,
            String message,
            boolean retryable
    ) {

        String text = message == null || message
                .isBlank() ? code
                        .name() : message;

        return new ErrorBody(
                1,
                code,
                text
                        .length() > MAX_MESSAGE ? text
                                .substring(
                                        0,
                                        MAX_MESSAGE
                                ) : text,
                null,
                retryable,
                UUID
                        .randomUUID()
                        .toString()
        );
    }
}
