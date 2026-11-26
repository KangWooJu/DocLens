package io.github.kangwooju.doclens.scheduler.dispatch.handler;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.type.TransportFailure;

/**
 * collector-api 통신
 * 예외 없이 응답을 그대로 돌려주고 분류하지 않음
 */
@Component
public class CollectorHandler {

    private final RestClient restClient;

    /** collector-api client 주입 */
    public CollectorHandler(@Qualifier("collectorClient") RestClient restClient) {

        this.restClient = restClient;
    }

    /** 수집 요청 접수, 본문은 행에 고정한 bytes 그대로 */
    public HttpReply submit(String collectionRequestJson) {

        return exchange(restClient
                .post()
                .uri("/internal/v1/collection-jobs")
                .body(collectionRequestJson));
    }

    /** 수집 job 상태 */
    public HttpReply status(UUID jobId) {

        return exchange(restClient
                .get()
                .uri(
                        "/internal/v1/collection-jobs/{jobId}",
                        jobId
                ));
    }

    /** 봉인된 readyRun 객체의 저장 bytes */
    public HttpReply readyRun(UUID jobId) {

        return exchange(restClient
                .get()
                .uri(
                        "/internal/v1/collection-jobs/{jobId}/ready-run",
                        jobId
                ));
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
