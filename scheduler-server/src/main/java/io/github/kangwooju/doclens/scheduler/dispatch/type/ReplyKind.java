package io.github.kangwooju.doclens.scheduler.dispatch.type;

/** 응답 분류, 성공과 재시도와 인증 실패와 확정 거절 */
public enum ReplyKind {

    OK,
    RETRY,
    AUTH,
    REJECTED
}
