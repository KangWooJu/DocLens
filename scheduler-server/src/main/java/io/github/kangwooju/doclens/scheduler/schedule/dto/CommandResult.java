package io.github.kangwooju.doclens.scheduler.schedule.dto;

import java.util.UUID;

import io.github.kangwooju.doclens.scheduler.schedule.type.CommandOutcome;

/**
 * slot 등록과 requeue 결과
 * 결과 이름과 종료 코드를 값으로 들고 있어 진입부가 enum에 의존하지 않음
 */
public record CommandResult(
        String outcome,
        UUID runId,
        int exitCode
) {

    /** 결과와 대상 run, run이 없으면 null */
    public static CommandResult of(
            CommandOutcome outcome,
            UUID runId
    ) {

        return new CommandResult(
                outcome
                        .name(),
                runId,
                outcome
                        .exitCode()
        );
    }
}
