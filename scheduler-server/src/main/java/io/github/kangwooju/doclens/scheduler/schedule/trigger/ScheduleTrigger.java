package io.github.kangwooju.doclens.scheduler.schedule.trigger;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.schedule.facade.ScheduleFacade;
import lombok.RequiredArgsConstructor;

/** 매일 slot 등록 일정 */
@Component
@Profile("!command")
@RequiredArgsConstructor
public class ScheduleTrigger {

    private final ScheduleFacade scheduleFacade;

    // 오늘 slot이 필요한지 짧게 확인
    @Scheduled(
            initialDelayString = "${doclens.scheduler.initial-delay}",
            fixedDelayString = "${doclens.scheduler.schedule-delay}"
    )
    void registerDue() {

        scheduleFacade
                .registerDue();
    }
}
