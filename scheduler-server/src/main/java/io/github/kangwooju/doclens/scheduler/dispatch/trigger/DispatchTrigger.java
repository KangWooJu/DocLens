package io.github.kangwooju.doclens.scheduler.dispatch.trigger;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.dispatch.facade.DispatchFacade;
import lombok.RequiredArgsConstructor;

/** 수집 요청 전달 일정 */
@Component
@Profile("!command")
@RequiredArgsConstructor
public class DispatchTrigger {

    private final DispatchFacade dispatchFacade;

    // 전달할 run이나 조회할 job이 있는지 짧게 확인
    @Scheduled(
            initialDelayString = "${doclens.scheduler.initial-delay}",
            fixedDelayString = "${doclens.scheduler.dispatch-delay}"
    )
    void dispatchNext() {

        dispatchFacade
                .dispatchNext();
    }
}
