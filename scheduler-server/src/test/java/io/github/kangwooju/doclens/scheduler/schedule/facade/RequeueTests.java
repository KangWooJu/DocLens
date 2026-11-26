package io.github.kangwooju.doclens.scheduler.schedule.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.scheduler.schedule.dto.CommandResult;
import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Reply;
import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Request;
import io.github.kangwooju.doclens.testsupport.FakePeers;
import io.github.kangwooju.doclens.testsupport.RunTestSupport;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("지금 수집과 실패 run 되돌림")
class RequeueTests extends RunTestSupport {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    private static final String JOBS = "/internal/v1/collection-jobs";

    @Test
    @DisplayName("같은 label은 행 하나, 활성 run이 있으면 다른 label도 대기, FAILED가 아니면 되돌리지 않음")
    void registersOneRunPerLabel() {

        CommandResult created = scheduleFacade
                .collectNow("same");
        CommandResult duplicate = scheduleFacade
                .collectNow("same");
        CommandResult blocked = scheduleFacade
                .collectNow("other");

        assertEquals(
                "CREATED",
                created
                        .outcome()
        );
        assertEquals(
                "DUPLICATE_SLOT",
                duplicate
                        .outcome()
        );
        assertEquals(
                created
                        .runId(),
                duplicate
                        .runId()
        );
        assertEquals(
                "ACTIVE_RUN_EXISTS",
                blocked
                        .outcome()
        );
        assertEquals(
                3,
                blocked
                        .exitCode()
        );
        assertEquals(
                "NOT_FAILED",
                scheduleFacade
                        .requeue(
                                created
                                        .runId(),
                                false
                        )
                        .outcome()
        );
        assertEquals(
                1,
                jdbcClient
                        .sql("SELECT count(*) FROM scheduler.runs")
                        .query(Integer.class)
                        .single()
        );
    }

    @Test
    @DisplayName("collector job 종결 실패 뒤 fresh requeue는 새 requestId와 새 job으로 끝까지")
    void freshRequeueStartsANewJob() {

        UUID runId = collect("fresh-1");
        dispatch();
        UUID firstJob = jobId(runId);
        PEERS
                .setJobState(
                        firstJob,
                        "FAILED_FINAL"
                );
        dispatch();
        assertFailed(
                runId,
                "COLLECTING",
                "REMOTE_TERMINAL"
        );
        assertEquals(
                firstJob,
                jobId(runId)
        );

        requeue(
                runId,
                true
        );
        dispatch();
        UUID secondJob = jobId(runId);
        assertNotEquals(
                firstJob,
                secondJob
        );
        assertEquals(
                2,
                distinctSubmittedRequestIds()
        );

        finish(runId);
    }

    @Test
    @DisplayName("collector job 종결 실패 뒤 job이 되살아나면 기본 requeue는 같은 job을 따라가 끝까지, 새 접수 없음")
    void defaultRequeueFollowsTheResumedJob() {

        UUID runId = collect("resume-1");
        dispatch();
        UUID job = jobId(runId);
        PEERS
                .setJobState(
                        job,
                        "FAILED_FINAL"
                );
        dispatch();
        assertFailed(
                runId,
                "COLLECTING",
                "REMOTE_TERMINAL"
        );

        PEERS
                .setJobState(
                        job,
                        "QUEUED"
                );
        requeue(
                runId,
                false
        );
        Map<String, Object> row = row(runId);
        assertEquals(
                "SENT",
                row
                        .get("delivery_state")
        );
        dispatch();
        assertEquals(
                "COLLECTING",
                row(runId)
                        .get("state")
        );

        finish(runId);
        assertEquals(
                job,
                jobId(runId)
        );
        assertEquals(
                1,
                PEERS
                        .collector()
                        .requests(
                                "POST",
                                JOBS
                        )
                        .size()
        );
    }

    @Test
    @DisplayName("접수 거절 422 뒤 requeue는 새 requestId로 다시 접수")
    void requeueAfterSubmitRejection() {

        PEERS
                .nextSubmit(Reply
                        .of(
                                422,
                                FakePeers
                                        .error(
                                                "SOURCE_UNAPPROVED",
                                                false
                                        )
                        ));
        UUID runId = collect("reject-1");
        dispatch();
        assertFailed(
                runId,
                "COLLECTING",
                "REJECTED"
        );
        assertEquals(
                "SOURCE_UNAPPROVED",
                row(runId)
                        .get("remote_state")
        );

        requeue(
                runId,
                false
        );
        dispatch();
        assertEquals(
                2,
                distinctSubmittedRequestIds()
        );
        assertEquals(
                "SENT",
                row(runId)
                        .get("delivery_state")
        );
    }

    @Test
    @DisplayName("처리 409 뒤 requeue는 같은 job으로 처리 요청만 새 requestId로 다시 만듦")
    void requeueAfterProcessingRejectionKeepsTheJob() {

        UUID runId = toProcessing("stale-1");
        UUID job = jobId(runId);
        PEERS
                .nextProcess(Reply
                        .of(
                                409,
                                FakePeers
                                        .error(
                                                "STALE_OWNER",
                                                false
                                        )
                        ));
        handoff();
        assertFailed(
                runId,
                "PROCESSING",
                "REJECTED"
        );
        String firstRequest = (String) row(runId)
                .get("process_request");

        requeue(
                runId,
                false
        );
        assertNull(row(runId)
                .get("process_request"));
        handoff();

        Map<String, Object> row = row(runId);
        assertEquals(
                "DONE",
                row
                        .get("state")
        );
        assertEquals(
                job,
                jobId(runId)
        );
        assertNotEquals(
                requestId(firstRequest),
                requestId((String) row
                        .get("process_request"))
        );
        assertEquals(
                1,
                PEERS
                        .collector()
                        .requests(
                                "POST",
                                JOBS
                        )
                        .size()
        );
    }

    @Test
    @DisplayName("처리 422 INPUT_INCOMPLETE는 수집 단계 실패, requeue는 새 requestId와 새 job")
    void inputRejectionRequeuesCollection() {

        UUID runId = toProcessing("incomplete-1");
        UUID firstJob = jobId(runId);
        PEERS
                .nextProcess(Reply
                        .of(
                                422,
                                FakePeers
                                        .error(
                                                "INPUT_INCOMPLETE",
                                                false
                                        )
                        ));
        handoff();
        assertFailed(
                runId,
                "COLLECTING",
                "REJECTED"
        );

        requeue(
                runId,
                false
        );
        dispatch();
        assertNotEquals(
                firstJob,
                jobId(runId)
        );
        assertEquals(
                2,
                distinctSubmittedRequestIds()
        );
    }

    @Test
    @DisplayName("더 늦은 DONE run이 있으면 옛 run의 처리 단계 requeue는 SUPERSEDED")
    void olderProcessingRequeueIsSuperseded() {

        UUID older = toProcessing("old-1");
        PEERS
                .nextProcess(Reply
                        .of(
                                409,
                                FakePeers
                                        .error(
                                                "HASH_MISMATCH",
                                                false
                                        )
                        ));
        handoff();
        assertFailed(
                older,
                "PROCESSING",
                "REJECTED"
        );

        UUID newer = toProcessing("new-1");
        handoff();
        assertEquals(
                "DONE",
                row(newer)
                        .get("state")
        );

        CommandResult result = scheduleFacade
                .requeue(
                        older,
                        false
                );
        assertEquals(
                "SUPERSEDED",
                result
                        .outcome()
        );
        assertEquals(
                "FAILED",
                row(older)
                        .get("state")
        );
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

    // 현재 job을 성공시키고 처리까지 끝냄
    private void finish(UUID runId) {

        PEERS
                .setJobState(
                        jobId(runId),
                        "SUCCEEDED"
                );
        dispatch();
        handoff();
        assertEquals(
                "DONE",
                row(runId)
                        .get("state")
        );
    }

    // requeue 성공 확인
    private void requeue(
            UUID runId,
            boolean fresh
    ) {

        assertEquals(
                "REQUEUED",
                scheduleFacade
                        .requeue(
                                runId,
                                fresh
                        )
                        .outcome()
        );
    }

    // FAILED와 실패 단계, 원인
    private void assertFailed(
            UUID runId,
            String failedStage,
            String failureKind
    ) {

        Map<String, Object> row = row(runId);
        assertEquals(
                "FAILED",
                row
                        .get("state")
        );
        assertEquals(
                failedStage,
                row
                        .get("failed_stage")
        );
        assertEquals(
                failureKind,
                row
                        .get("failure_kind")
        );
    }

    // 접수 요청에 쓰인 서로 다른 requestId 수
    private long distinctSubmittedRequestIds() {

        List<Request> posts = PEERS
                .collector()
                .requests(
                        "POST",
                        JOBS
                );

        return posts
                .stream()
                .map(request -> requestId(request
                        .body()))
                .distinct()
                .count();
    }

    // JSON 본문의 requestId
    private static String requestId(String body) {

        return JSON_MAPPER
                .readTree(body)
                .get("requestId")
                .stringValue();
    }
}
