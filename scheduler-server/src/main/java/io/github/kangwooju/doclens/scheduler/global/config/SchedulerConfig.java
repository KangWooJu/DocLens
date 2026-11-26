package io.github.kangwooju.doclens.scheduler.global.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.kangwooju.doclens.scheduler.global.config.dto.SchedulerProperties;

/** scheduler 설정 record와 slot 날짜용 시계 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig {

    /** slot 날짜와 요청 시각의 시계, lease와 재시도 시각은 DB now() */
    @Bean
    public Clock clock() {

        return Clock
                .systemUTC();
    }
}
