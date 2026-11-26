package io.github.kangwooju.doclens.scheduler.dispatch.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.type.ReplyKind;
import io.github.kangwooju.doclens.scheduler.dispatch.type.TransportFailure;

@DisplayName("응답 분류와 재시도 간격")
class DeliveryPolicyTests {

    private final DeliveryPolicy policy = new DeliveryPolicy();

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(nullValues = "none", value = {
            "200, none, none, OK",
            "202, none, none, OK",
            "401, AUTH_REQUIRED, false, AUTH",
            "403, FORBIDDEN, false, AUTH",
            "409, STALE_OWNER, false, REJECTED",
            "422, INPUT_INCOMPLETE, false, REJECTED",
            "500, INTERNAL_ERROR, false, REJECTED",
            "503, INTERNAL_ERROR, true, RETRY",
            "409, INPUT_NOT_SEALED, true, RETRY",
            "404, NOT_FOUND, false, REJECTED",
            "502, none, none, RETRY",
            "408, none, none, RETRY",
            "429, none, none, RETRY",
            "404, none, none, REJECTED",
            "400, none, none, REJECTED"
    })
    @DisplayName("계약 오류의 retryable을 상태 코드보다 먼저 보는 분류")
    void classifiesReplies(
            int status,
            String code,
            Boolean retryable,
            ReplyKind expected
    ) {

        String body = code == null ? "<html>gateway</html>" : """
                {"schemaVersion":1,"code":"%s","message":"m","requestId":null,"retryable":%s,"traceId":"t"}
                """
                .formatted(
                        code,
                        retryable
                );

        Delivery delivery = policy
                .classify(new HttpReply(
                        status,
                        body,
                        TransportFailure.NONE
                ));

        assertEquals(
                expected,
                delivery
                        .kind()
        );
        assertEquals(
                code,
                delivery
                        .errorCode()
        );
    }

    @Test
    @DisplayName("연결 실패와 read timeout은 재시도")
    void transportFailuresRetry() {

        for (TransportFailure failure : new TransportFailure[] {TransportFailure.CONNECT, TransportFailure.TIMEOUT}) {
            Delivery delivery = policy
                    .classify(new HttpReply(
                            0,
                            null,
                            failure
                    ));

            assertEquals(
                    ReplyKind.RETRY,
                    delivery
                            .kind()
            );
            assertEquals(
                    "transport " + failure,
                    delivery
                            .detail()
            );
        }
    }

    @Test
    @DisplayName("재시도 간격 5, 30, 120초 뒤 300초 고정에 0에서 3초 흔들림, 인증 실패는 300초")
    void backsOff() {

        long[] expectedSeconds = {5, 30, 120, 300, 300, 300};

        for (int attempt = 1; attempt <= expectedSeconds.length; attempt++) {
            Duration delay = policy
                    .nextDelay(
                            attempt,
                            ReplyKind.RETRY
                    );
            assertWithinJitter(
                    expectedSeconds[attempt - 1],
                    delay
            );
        }

        assertWithinJitter(
                300,
                policy
                        .nextDelay(
                                1,
                                ReplyKind.AUTH
                        )
        );
    }

    // 기준 초 이상, 기준 초 더하기 3초 이하
    private static void assertWithinJitter(
            long baseSeconds,
            Duration delay
    ) {

        long millis = delay
                .toMillis();

        assertTrue(
                millis >= baseSeconds * 1000 && millis <= baseSeconds * 1000 + 3000,
                () -> "delay " + delay
        );
    }
}
