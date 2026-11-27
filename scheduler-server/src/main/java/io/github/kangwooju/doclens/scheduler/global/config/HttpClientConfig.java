package io.github.kangwooju.doclens.scheduler.global.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.kangwooju.doclens.scheduler.global.config.dto.SchedulerProperties;

/**
 * 수신자별 HTTP client
 * bearer와 JSON Content-Type 기본 header, 처리 호출만 긴 read timeout
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    /** collector-api 접수와 조회 client */
    @Bean
    public RestClient collectorClient(SchedulerProperties properties) {

        return client(
                properties,
                properties
                        .collectorUrl(),
                properties
                        .readTimeout()
        );
    }

    /** pipeline 발행 조회 client */
    @Bean
    public RestClient pipelineClient(SchedulerProperties properties) {

        return client(
                properties,
                properties
                        .pipelineUrl(),
                properties
                        .readTimeout()
        );
    }

    /** pipeline 동기 처리 client, Build와 발행이 끝날 때까지 기다림 */
    @Bean
    public RestClient pipelineProcessClient(SchedulerProperties properties) {

        return client(
                properties,
                properties
                        .pipelineUrl(),
                properties
                        .processReadTimeout()
        );
    }

    // 주소와 read timeout만 다른 HTTP/1.1 client
    private static RestClient client(
            SchedulerProperties properties,
            URI baseUrl,
            Duration readTimeout
    ) {

        HttpClient httpClient = HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties
                        .connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory
                .setReadTimeout(readTimeout);

        return RestClient
                .builder()
                .baseUrl(baseUrl
                        .toString())
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties
                                .token()
                )
                .defaultHeader(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .build();
    }
}
