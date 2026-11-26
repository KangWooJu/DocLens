package io.github.kangwooju.doclens.scheduler.dispatch.policy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.type.ReplyKind;
import io.github.kangwooju.doclens.scheduler.dispatch.type.TransportFailure;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 응답 분류와 재시도 간격
 * 계약 오류의 retryable을 상태 코드보다 먼저 보고, 재시도 횟수 상한은 없음
 */
@Component
public class DeliveryPolicy {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    // 일시 오류 재시도 간격, 마지막 값을 계속 사용
    private static final List<Duration> RETRY_DELAYS = List
            .of(
                    Duration
                            .ofSeconds(5),
                    Duration
                            .ofSeconds(30),
                    Duration
                            .ofSeconds(120),
                    Duration
                            .ofSeconds(300)
            );

    // 인증 실패 재시도 간격, 토큰을 고치면 저절로 회복
    private static final Duration AUTH_DELAY = Duration
            .ofSeconds(300);

    // 재시도가 한꺼번에 몰리지 않게 더하는 흔들림 상한(밀리초)
    private static final int JITTER_MILLIS = 3000;

    // last_error에 남길 본문 길이 상한
    private static final int DETAIL_LIMIT = 500;

    /** 응답 하나의 분류, 전송 실패와 인증 실패와 계약 오류와 상태 코드 순 */
    public Delivery classify(HttpReply reply) {

        if (reply
                .transportFailure() != TransportFailure.NONE) {
            return new Delivery(
                    reply,
                    ReplyKind.RETRY,
                    null,
                    "transport " + reply
                            .transportFailure()
            );
        }

        int status = reply
                .status();
        JsonNode error = contractError(reply
                .body());
        String code = error == null ? null : error
                .get("code")
                .stringValue();
        String detail = detail(
                status,
                code,
                error == null ? reply
                        .body() : error
                                .get("message")
                                .stringValue()
        );

        return new Delivery(
                reply,
                kind(
                        status,
                        error
                ),
                code,
                detail
        );
    }

    /** 다음 시도까지 기다릴 시간, 시도 횟수는 이번 시도를 포함한 1부터 */
    public Duration nextDelay(
            int attemptCount,
            ReplyKind kind
    ) {

        Duration base = kind == ReplyKind.AUTH ? AUTH_DELAY : RETRY_DELAYS
                .get(Math
                        .min(
                                Math
                                        .max(
                                                attemptCount,
                                                1
                                        ) - 1,
                                RETRY_DELAYS
                                        .size() - 1
                        ));

        return base
                .plusMillis(ThreadLocalRandom
                        .current()
                        .nextInt(JITTER_MILLIS + 1));
    }

    // 상태 코드와 계약 오류로 정한 분류
    private static ReplyKind kind(
            int status,
            JsonNode error
    ) {

        if (status >= 200 && status < 300) {
            return ReplyKind.OK;
        }

        if (status == 401 || status == 403) {
            return ReplyKind.AUTH;
        }

        if (error != null) {
            return error
                    .get("retryable")
                    .booleanValue() ? ReplyKind.RETRY : ReplyKind.REJECTED;
        }

        boolean transientStatus = status == 408 || status == 429 || status >= 500;

        return transientStatus ? ReplyKind.RETRY : ReplyKind.REJECTED;
    }

    // 계약 오류 본문이면 그 객체, code와 message와 retryable이 없으면 null
    private static JsonNode contractError(String body) {

        if (body == null || body
                .isBlank()) {
            return null;
        }

        try {
            JsonNode node = JSON_MAPPER
                    .readTree(body);
            boolean contract = node
                    .path("code")
                    .isString()
                    && node
                            .path("message")
                            .isString()
                    && node
                            .path("retryable")
                            .isBoolean();

            return contract ? node : null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    // last_error용 짧은 문구
    private static String detail(
            int status,
            String code,
            String message
    ) {

        String text = "HTTP " + status + (code == null ? "" : " " + code) + (message == null ? "" : ": " + message);

        return text
                .length() > DETAIL_LIMIT ? text
                        .substring(
                                0,
                                DETAIL_LIMIT
                        ) : text;
    }
}
