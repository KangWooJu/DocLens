package io.github.kangwooju.doclens.scheduler.dispatch.dto;

import io.github.kangwooju.doclens.scheduler.dispatch.type.ReplyKind;

/**
 * 분류한 응답
 * 계약 오류 code가 없으면 null, detail은 last_error 기록용 짧은 문구
 */
public record Delivery(
        HttpReply reply,
        ReplyKind kind,
        String errorCode,
        String detail
) {

    /** 상대가 말한 상태로 남길 값, 계약 오류 code가 없으면 HTTP 상태 */
    public String remoteState() {

        return errorCode != null ? errorCode : "HTTP " + reply
                .status();
    }
}
