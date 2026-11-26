package io.github.kangwooju.doclens.scheduler.dispatch.facade;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.service.DispatchService;
import io.github.kangwooju.doclens.scheduler.global.transaction.TransactionExecutor;
import lombok.RequiredArgsConstructor;

/**
 * 수집 요청 전달 진입점
 * 짧은 claim 트랜잭션, 트랜잭션 밖 HTTP, 짧은 기록 트랜잭션
 */
@Component
@RequiredArgsConstructor
public class DispatchFacade {

    private final DispatchService dispatchService;
    private final TransactionExecutor transactionExecutor;

    /** 차례가 된 수집 run 하나 전달 또는 조회, 처리한 run이 있으면 true */
    public boolean dispatchNext() {

        Optional<ClaimedRun> claimed = transactionExecutor
                .write(dispatchService::claim);

        if (claimed
                .isEmpty()) {
            return false;
        }

        ClaimedRun run = claimed
                .get();
        Delivery delivery = dispatchService
                .call(run);
        transactionExecutor
                .write(() -> dispatchService
                        .record(
                                run,
                                delivery
                        ));

        return true;
    }
}
