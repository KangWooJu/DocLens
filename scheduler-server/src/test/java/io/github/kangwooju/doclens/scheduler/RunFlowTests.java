package io.github.kangwooju.doclens.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.service.DispatchService;
import io.github.kangwooju.doclens.scheduler.dispatch.type.ReplyKind;
import io.github.kangwooju.doclens.scheduler.dispatch.type.TransportFailure;
import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Reply;
import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Request;
import io.github.kangwooju.doclens.testsupport.FakePeers;
import io.github.kangwooju.doclens.testsupport.RunTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("수집 요청에서 처리 인계까지 run 흐름")
class RunFlowTests extends RunTestSupport {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    @Autowired
    private DispatchService dispatchService;

    @Test
    @DisplayName("가짜 수신자 끝까지, 상태와 전달 상태와 상대 상태 변화, bearer와 JSON header")
    void runsToDone() {

        UUID runId = collect("e2e-1");
        assertState(
                runId,
                "COLLECTING",
                "PENDING",
                null
        );

        assertTrue(dispatch());
        assertState(
                runId,
                "COLLECTING",
                "SENT",
                "QUEUED"
        );

        assertTrue(dispatch());
        assertState(
                runId,
                "COLLECTING",
                "SENT",
                "QUEUED"
        );

        PEERS
                .setJobState(
                        jobId(runId),
                        "SUCCEEDED"
                );
        assertTrue(dispatch());
        assertState(
                runId,
                "PROCESSING",
                "PENDING",
                "SUCCEEDED"
        );
        assertNotNull(row(runId)
                .get("ready_run_ref"));

        assertTrue(handoff());
        assertState(
                runId,
                "DONE",
                "SENT",
                "SUCCEEDED"
        );
        assertNotNull(row(runId)
                .get("result"));
        assertFalse(dispatch());
        assertFalse(handoff());

        List<Request> all = new ArrayList<>(PEERS
                .collector()
                .requests());
        all
                .addAll(PEERS
                        .pipeline()
                        .requests());
        assertEquals(
                6,
                all
                        .size()
        );

        for (Request request : all) {
            assertEquals(
                    "Bearer " + FakePeers.TOKEN,
                    request
                            .authorization()
            );
            assertEquals(
                    "application/json",
                    request
                            .contentType()
            );
        }

        assertEquals(
                "project=spring-boot&exactVersion=4.0.0",
                PEERS
                        .pipeline()
                        .requests(
                                "GET",
                                "/internal/v1/publications/latest"
                        )
                        .getFirst()
                        .query()
        );
    }

    @Test
    @DisplayName("처리 뒤 연결이 끊기면 같은 bytes와 같은 requestId로 재전송, 준비 단계는 다시 하지 않음")
    void resendsTheSameBodyAfterADroppedConnection() {

        UUID runId = toProcessing("drop-1");
        PEERS
                .nextProcess(Reply
                        .dropped());

        assertTrue(handoff());
        Map<String, Object> afterDrop = row(runId);
        assertEquals(
                "PROCESSING",
                afterDrop
                        .get("state")
        );
        assertEquals(
                "transport CONNECT",
                afterDrop
                        .get("last_error")
        );

        assertTrue(handoff());
        assertEquals(
                "DONE",
                row(runId)
                        .get("state")
        );

        List<Request> posts = PEERS
                .pipeline()
                .requests(
                        "POST",
                        "/internal/v1/processing-jobs"
                );
        assertEquals(
                2,
                posts
                        .size()
        );
        assertEquals(
                posts
                        .get(0)
                        .body(),
                posts
                        .get(1)
                        .body()
        );
        assertEquals(
                posts
                        .get(0)
                        .body(),
                row(runId)
                        .get("process_request")
        );
        assertEquals(
                1,
                PEERS
                        .pipeline()
                        .requests(
                                "GET",
                                "/internal/v1/publications/latest"
                        )
                        .size()
        );
        assertEquals(
                1,
                PEERS
                        .collector()
                        .requests(
                                "GET",
                                "/internal/v1/collection-jobs/" + jobId(runId) + "/ready-run"
                        )
                        .size()
        );
    }

    @Test
    @DisplayName("처리 응답이 read timeout을 넘기면 재시도, 다음 시도 시각 증가, lease 해제")
    void retriesAfterReadTimeout() {

        UUID runId = toProcessing("timeout-1");
        PEERS
                .nextProcess(Reply
                        .delayed(
                                3000,
                                200,
                                "{}"
                        ));

        assertTrue(handoff());
        Map<String, Object> row = row(runId);
        assertEquals(
                "PROCESSING",
                row
                        .get("state")
        );
        assertEquals(
                "transport TIMEOUT",
                row
                        .get("last_error")
        );
        assertEquals(
                1,
                row
                        .get("attempt_count")
        );
        assertNull(row
                .get("lease_until"));
        assertTrue(jdbcClient
                .sql("SELECT next_attempt_at > now() + interval '4 seconds' FROM scheduler.runs WHERE run_id = :runId")
                .param(
                        "runId",
                        runId
                )
                .query(Boolean.class)
                .single());
    }

    @Test
    @DisplayName("lease가 지나면 다른 호출이 다시 잡고, 늦은 기록은 0행")
    void reclaimsAnExpiredLease() {

        UUID runId = collect("lease-1");
        ClaimedRun first = dispatchService
                .claim()
                .orElseThrow();
        assertTrue(dispatchService
                .claim()
                .isEmpty());

        jdbcClient
                .sql("UPDATE scheduler.runs SET lease_until = now() - interval '1 second'")
                .update();
        ClaimedRun second = dispatchService
                .claim()
                .orElseThrow();
        assertEquals(
                runId,
                second
                        .runId()
        );
        assertEquals(
                2,
                second
                        .attemptCount()
        );

        Delivery timeout = new Delivery(
                new HttpReply(
                        0,
                        null,
                        TransportFailure.TIMEOUT
                ),
                ReplyKind.RETRY,
                null,
                "transport TIMEOUT"
        );
        assertFalse(dispatchService
                .record(
                        first,
                        timeout
                ));
        assertTrue(jdbcClient
                .sql("SELECT lease_until = :leaseUntil FROM scheduler.runs WHERE run_id = :runId")
                .param(
                        "leaseUntil",
                        second
                                .leaseUntil()
                )
                .param(
                        "runId",
                        runId
                )
                .query(Boolean.class)
                .single());
        assertNull(row(runId)
                .get("last_error"));
    }

    @Test
    @DisplayName("처리 요청의 발행 번호 기대값은 발행이 없으면 0, 있으면 최신 번호")
    void fixesExpectedPublicationSequence() {

        UUID first = toProcessing("cas-1");
        assertTrue(handoff());
        assertEquals(
                0,
                expectedSequence(first)
        );

        PEERS
                .setLatestSequence(3L);
        UUID second = toProcessing("cas-2");
        assertTrue(handoff());
        assertEquals(
                3,
                expectedSequence(second)
        );
        assertEquals(
                "DONE",
                row(second)
                        .get("state")
        );
    }

    @Test
    @DisplayName("readyRun bytes가 job 상태의 hash와 다르면 수집 단계 무결성 실패, 처리 요청 없음")
    void failsOnReadyRunIntegrityMismatch() {

        UUID runId = toProcessing("integrity-1");
        PEERS
                .serveReadyRun("{\"tampered\":true}");

        assertTrue(handoff());
        Map<String, Object> row = row(runId);
        assertEquals(
                "FAILED",
                row
                        .get("state")
        );
        assertEquals(
                "COLLECTING",
                row
                        .get("failed_stage")
        );
        assertEquals(
                "INTEGRITY",
                row
                        .get("failure_kind")
        );
        assertNull(row
                .get("process_request"));
        assertTrue(PEERS
                .pipeline()
                .requests()
                .isEmpty());
    }

    // 수집 성공까지 움직인 run
    private UUID toProcessing(String label) {

        UUID runId = collect(label);
        dispatch();
        PEERS
                .setJobState(
                        jobId(runId),
                        "SUCCEEDED"
                );
        dispatch();
        assertEquals(
                "PROCESSING",
                row(runId)
                        .get("state")
        );

        return runId;
    }

    // 고정한 처리 요청의 첫 projection 발행 번호 기대값
    private long expectedSequence(UUID runId) {

        JsonNode request = JSON_MAPPER
                .readTree((String) row(runId)
                        .get("process_request"));

        return request
                .get("projections")
                .get(0)
                .get("expectedPublicationSequence")
                .longValue();
    }

    // run 상태, 전달 상태, 상대 상태
    private void assertState(
            UUID runId,
            String state,
            String deliveryState,
            String remoteState
    ) {

        Map<String, Object> row = row(runId);
        assertEquals(
                state,
                row
                        .get("state")
        );
        assertEquals(
                deliveryState,
                row
                        .get("delivery_state")
        );
        assertEquals(
                remoteState,
                row
                        .get("remote_state")
        );
    }
}
