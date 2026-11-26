package io.github.kangwooju.doclens.scheduler.handoff.trigger;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.handoff.facade.HandoffFacade;
import lombok.RequiredArgsConstructor;

/** 처리 인계 일정 */
@Component
@Profile("!command")
@RequiredArgsConstructor
public class HandoffTrigger {

    private final HandoffFacade handoffFacade;

    /** 일정 스레드는 넘기고 바로 돌아옴, 처리 호출 대기는 handoffExecutor 스레드 하나에서만 */
    @Async("handoffExecutor")
    @Scheduled(
            initialDelayString = "${doclens.scheduler.initial-delay}",
            fixedDelayString = "${doclens.scheduler.handoff-delay}"
    )
    public void handoffNext() {

        handoffFacade
                .handoffNext();
    }
}
