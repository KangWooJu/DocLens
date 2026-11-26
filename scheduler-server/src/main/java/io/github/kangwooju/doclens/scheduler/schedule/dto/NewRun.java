package io.github.kangwooju.doclens.scheduler.schedule.dto;

import java.util.UUID;

/** 새 run 행, 수집 요청은 보낼 bytes 그대로 */
public record NewRun(
        UUID runId,
        String scheduleKey,
        String slotKey,
        String collectionRequest
) {
}
