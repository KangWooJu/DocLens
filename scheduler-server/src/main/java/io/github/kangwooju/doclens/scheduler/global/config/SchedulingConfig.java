package io.github.kangwooju.doclens.scheduler.global.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 일정 실행과 handoff 전용 실행자, command profile에서는 꺼짐 */
@Configuration(proxyBeanMethods = false)
@Profile("!command")
@EnableScheduling
@EnableAsync
public class SchedulingConfig {

    /** 처리 호출 대기 전용 스레드 1개, 대기열 0, 앞 호출이 끝나지 않았으면 그 tick 버림 */
    @Bean
    public ThreadPoolTaskExecutor handoffExecutor() {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor
                .setCorePoolSize(1);
        executor
                .setMaxPoolSize(1);
        executor
                .setQueueCapacity(0);
        executor
                .setThreadNamePrefix("handoff-");
        executor
                .setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor
                .setWaitForTasksToCompleteOnShutdown(true);
        executor
                .setAwaitTerminationSeconds(20);

        return executor;
    }
}
