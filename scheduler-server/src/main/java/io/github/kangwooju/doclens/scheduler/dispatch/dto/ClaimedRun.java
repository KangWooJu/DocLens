package io.github.kangwooju.doclens.scheduler.dispatch.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.kangwooju.doclens.scheduler.dispatch.type.DeliveryState;

/**
 * lease로 잡은 run 한 행
 * 기록은 같은 run, 같은 상태, 같은 lease일 때만
 */
public record ClaimedRun(
        UUID runId,
        String scheduleKey,
        DeliveryState deliveryState,
        String collectionRequest,
        UUID collectorJobId,
        String readyRunRef,
        String processRequest,
        int attemptCount,
        OffsetDateTime leaseUntil
) {
}
