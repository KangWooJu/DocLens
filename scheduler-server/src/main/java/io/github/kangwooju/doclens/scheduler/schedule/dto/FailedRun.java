package io.github.kangwooju.doclens.scheduler.schedule.dto;

import java.util.UUID;

import io.github.kangwooju.doclens.scheduler.dispatch.type.FailedStage;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailureKind;

/** requeue 대상 run, 실패가 아니면 단계와 원인은 null */
public record FailedRun(
        UUID runId,
        String scheduleKey,
        String state,
        FailedStage failedStage,
        FailureKind failureKind
) {
}
