package io.github.kangwooju.doclens.scheduler.handoff.facade;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.scheduler.handoff.dto.Preparation;
import io.github.kangwooju.doclens.scheduler.handoff.service.HandoffService;
import lombok.RequiredArgsConstructor;

/**
 * 처리 인계 진입점
 * 본문 고정 트랜잭션이 전송보다 먼저, 재시도는 고정한 본문만 다시 보냄
 */
@Component
@RequiredArgsConstructor
public class HandoffFacade {

    private final HandoffService handoffService;
    private final TransactionExecutor transactionExecutor;

    /** 차례가 된 처리 run 하나 인계, 처리한 run이 있으면 true */
    public boolean handoffNext() {

        Optional<ClaimedRun> claimed = transactionExecutor
                .write(handoffService::claim);

        if (claimed
                .isEmpty()) {
            return false;
        }

        ClaimedRun run = claimed
                .get();
        String processRequest = run
                .processRequest();

        if (processRequest == null) {
            Preparation preparation = handoffService
                    .prepare(run);

            if (preparation
                    .processRequest() == null) {
                transactionExecutor
                        .write(() -> handoffService
                                .recordPreparation(
                                        run,
                                        preparation
                                ));

                return true;
            }

            processRequest = preparation
                    .processRequest();
            String fixed = processRequest;

            if (!transactionExecutor
                    .write(() -> handoffService
                            .fix(
                                    run,
                                    fixed
                            ))) {
                return true;
            }
        }

        Delivery delivery = handoffService
                .call(processRequest);
        transactionExecutor
                .write(() -> handoffService
                        .record(
                                run,
                                delivery
                        ));

        return true;
    }
}
