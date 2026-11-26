package io.github.kangwooju.doclens.scheduler.handoff.dto;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailedStage;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailureKind;

/**
 * 처리 요청 준비 결과
 * 성공이면 본문만, 실패면 분류한 응답과 거절일 때 기록할 단계와 원인
 */
public record Preparation(
        String processRequest,
        Delivery failure,
        FailedStage failedStage,
        FailureKind failureKind
) {

    /** 보낼 처리 요청 본문 */
    public static Preparation ready(String processRequest) {

        return new Preparation(
                processRequest,
                null,
                null,
                null
        );
    }

    /** 준비 중 실패, 재시도 분류면 단계와 원인은 쓰이지 않음 */
    public static Preparation failed(
            Delivery failure,
            FailedStage failedStage,
            FailureKind failureKind
    ) {

        return new Preparation(
                null,
                failure,
                failedStage,
                failureKind
        );
    }
}
