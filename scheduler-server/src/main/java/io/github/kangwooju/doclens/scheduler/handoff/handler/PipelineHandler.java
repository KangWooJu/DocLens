package io.github.kangwooju.doclens.scheduler.handoff.handler;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.type.TransportFailure;
import io.github.kangwooju.doclens.scheduler.handoff.dto.PackScope;

/**
 * pipeline 통신
 * 예외 없이 응답을 그대로 돌려주고 분류하지 않음, 처리 호출만 긴 read timeout
 */
@Component
public class PipelineHandler {

    private final RestClient restClient;
    private final RestClient processClient;

    /** 짧은 조회 client와 긴 처리 client 주입 */
    public PipelineHandler(
            @Qualifier("pipelineClient") RestClient restClient,
            @Qualifier("pipelineProcessClient") RestClient processClient
    ) {

        this.restClient = restClient;
        this.processClient = processClient;
    }

    /** 동기 처리 요청, 200이면 Build와 발행까지 끝난 receipt */
    public HttpReply process(String processRequestJson) {

        return exchange(processClient
                .post()
                .uri("/internal/v1/processing-jobs")
                .body(processRequestJson));
    }

    /** Pack 범위의 최신 발행 manifest */
    public HttpReply latestPublication(PackScope scope) {

        return exchange(restClient
                .get()
                .uri(builder -> builder
                        .path("/internal/v1/publications/latest")
                        .queryParam(
                                "project",
                                scope
                                        .project()
                        )
                        .queryParam(
                                "exactVersion",
                                scope
                                        .exactVersion()
                        )
                        .build()));
    }

    // 상태 코드와 상관없이 응답을 그대로 받고, 연결 실패와 timeout은 전송 실패로 바꿈
    private static HttpReply exchange(RestClient.RequestHeadersSpec<?> request) {

        try {
            ResponseEntity<String> response = request
                    .retrieve()
                    .onStatus(
                            status -> true,
                            (httpRequest, httpResponse) -> {
                            }
                    )
                    .toEntity(String.class);

            return new HttpReply(
                    response
                            .getStatusCode()
                            .value(),
                    response
                            .getBody(),
                    TransportFailure.NONE
            );
        } catch (ResourceAccessException exception) {
            return new HttpReply(
                    0,
                    null,
                    failure(exception)
            );
        }
    }

    // 원인 사슬에 read timeout이 있으면 TIMEOUT, 그 밖 연결 문제는 CONNECT
    private static TransportFailure failure(Throwable exception) {

        for (Throwable cause = exception; cause != null; cause = cause
                .getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return TransportFailure.CONNECT;
            }

            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return TransportFailure.TIMEOUT;
            }
        }

        return TransportFailure.CONNECT;
    }
}
