package io.github.kangwooju.doclens.scheduler.dispatch.dto;

import io.github.kangwooju.doclens.scheduler.dispatch.type.TransportFailure;

/**
 * 원격 호출 하나의 응답
 * 전송 실패면 상태 0과 본문 null
 */
public record HttpReply(
        int status,
        String body,
        TransportFailure transportFailure
) {
}
