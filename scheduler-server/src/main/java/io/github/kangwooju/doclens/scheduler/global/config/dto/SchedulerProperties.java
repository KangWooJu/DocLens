package io.github.kangwooju.doclens.scheduler.global.config.dto;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * doclens.scheduler 설정
 * 수신자 주소와 bearer 원본, 요청 틀, 처리 설정 hash, 일정, timeout과 lease
 */
@ConfigurationProperties("doclens.scheduler")
public record SchedulerProperties(
        String token,
        URI collectorUrl,
        URI pipelineUrl,
        Path requestFile,
        String processingConfigHash,
        String scheduleKey,
        Collect collect,
        Duration connectTimeout,
        Duration readTimeout,
        Duration processReadTimeout,
        Duration lease,
        Duration statusInterval
) {

    /** 매일 수집 slot 설정, 시각은 Asia/Seoul HH:mm */
    public record Collect(
            boolean enabled,
            String time
    ) {
    }
}
