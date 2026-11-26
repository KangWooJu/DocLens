package io.github.kangwooju.doclens.scheduler.schedule.facade;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.scheduler.schedule.dto.CommandResult;
import io.github.kangwooju.doclens.scheduler.schedule.service.ScheduleService;
import io.github.kangwooju.doclens.scheduler.schedule.type.CommandOutcome;
import lombok.RequiredArgsConstructor;

/** 일정 등록, 지금 수집, 실패 run 되돌림 진입점 */
@Component
@RequiredArgsConstructor
public class ScheduleFacade {

    private final ScheduleService scheduleService;
    private final TransactionExecutor transactionExecutor;

    /** 오늘 slot이 필요하면 run 행, 만들었으면 true */
    public boolean registerDue() {

        Optional<String> slot = scheduleService
                .dueSlot();

        if (slot
                .isEmpty()) {
            return false;
        }

        CommandResult result = transactionExecutor
                .write(() -> scheduleService
                        .register(slot
                                .get()));

        return result
                .outcome()
                .equals(CommandOutcome.CREATED
                        .name());
    }

    /** manual:<label> slot의 run 행, 같은 label이면 새로 만들지 않음 */
    public CommandResult collectNow(String label) {

        return transactionExecutor
                .write(() -> scheduleService
                        .register("manual:" + label));
    }

    /** FAILED run 하나를 실패 단계로 되돌림 */
    public CommandResult requeue(
            UUID runId,
            boolean fresh
    ) {

        return transactionExecutor
                .write(() -> scheduleService
                        .requeue(
                                runId,
                                fresh
                        ));
    }
}
