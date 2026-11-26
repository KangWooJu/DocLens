package io.github.kangwooju.doclens.scheduler.handoff.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.scheduler.schedule.facade.ScheduleFacade;
import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Reply;
import io.github.kangwooju.doclens.testsupport.FakePeers;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest(properties = {
        "doclens.scheduler.initial-delay=0s",
        "doclens.scheduler.dispatch-delay=100ms",
        "doclens.scheduler.handoff-delay=100ms",
        "doclens.scheduler.status-interval=0s",
        "doclens.scheduler.token=" + FakePeers.TOKEN,
        "doclens.scheduler.processing-config-hash=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext
@DisplayName("일정으로 끝까지, 처리 호출 대기는 handoff 실행자 하나에서만")
class HandoffTriggerTests {

    private static final FakePeers PEERS = peers();

    @Autowired
    private ScheduleFacade scheduleFacade;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ThreadPoolTaskExecutor handoffExecutor;

    // 가짜 수신자 주소와 요청 틀 파일
    @DynamicPropertySource
    static void receivers(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.scheduler.collector-url",
                        () -> PEERS
                                .collector()
                                .uri()
                );
        registry
                .add(
                        "doclens.scheduler.pipeline-url",
                        () -> PEERS
                                .pipeline()
                                .uri()
                );
        registry
                .add(
                        "doclens.scheduler.request-file",
                        FakePeers::requestTemplateFile
                );
    }

    // 가짜 수신자 정지
    @AfterAll
    static void stopPeers() {

        PEERS
                .close();
    }

    @Test
    @DisplayName("처리 호출이 2초 걸리는 동안 수집 일정은 계속 돌고, 처리 호출은 동시에 하나, 겹친 tick은 버려짐")
    void handoffDoesNotBlockSchedules() throws InterruptedException {

        UUID otherJob = UUID
                .randomUUID();
        PEERS
                .setJobState(
                        otherJob,
                        "RUNNING"
                );
        jdbcClient
                .sql("""
                        INSERT INTO scheduler.runs (run_id, schedule_key, slot_key, delivery_state, collector_job_id,
                            collection_request)
                        VALUES (:runId, 'other-schedule', 'manual:other', 'SENT', :jobId, '{}')
                        """)
                .param(
                        "runId",
                        UUID
                                .randomUUID()
                )
                .param(
                        "jobId",
                        otherJob
                )
                .update();
        PEERS
                .nextProcess(Reply
                        .delayed(
                                2000,
                                200,
                                FakePeers
                                        .receipt()
                        ));

        UUID runId = scheduleFacade
                .collectNow("tick-1")
                .runId();
        waitFor(
                () -> jobOf(runId) != null,
                Duration
                        .ofSeconds(10)
        );
        PEERS
                .setJobState(
                        jobOf(runId),
                        "SUCCEEDED"
                );
        waitFor(
                () -> !PEERS
                        .pipeline()
                        .requests(
                                "POST",
                                "/internal/v1/processing-jobs"
                        )
                        .isEmpty(),
                Duration
                        .ofSeconds(10)
        );

        Instant handoffStarted = Instant
                .now();
        int pollsBefore = statusPolls(otherJob);
        Thread
                .sleep(1500);
        int pollsDuring = statusPolls(otherJob) - pollsBefore;

        assertTrue(
                pollsDuring >= 5,
                () -> "dispatch polls during the processing call: " + pollsDuring
        );
        assertEquals(
                1,
                handoffExecutor
                        .getActiveCount()
        );
        assertEquals(
                0,
                handoffExecutor
                        .getThreadPoolExecutor()
                        .getQueue()
                        .size()
        );
        assertTrue(Duration
                .between(
                        handoffStarted,
                        Instant
                                .now()
                )
                .toMillis() < 2000);

        waitFor(
                () -> "DONE".equals(stateOf(runId)),
                Duration
                        .ofSeconds(15)
        );
        assertEquals(
                1,
                PEERS
                        .pipeline()
                        .maxInFlight()
        );
    }

    // 조건이 참이 될 때까지 대기
    private static void waitFor(
            BooleanSupplier condition,
            Duration timeout
    ) throws InterruptedException {

        Instant deadline = Instant
                .now()
                .plus(timeout);

        while (!condition
                .getAsBoolean()) {
            assertTrue(
                    Instant
                            .now()
                            .isBefore(deadline),
                    "condition not met in time"
            );
            Thread
                    .sleep(50);
        }
    }

    // 다른 일정 job의 상태 조회 횟수
    private static int statusPolls(UUID jobId) {

        return PEERS
                .collector()
                .requests(
                        "GET",
                        "/internal/v1/collection-jobs/" + jobId
                )
                .size();
    }

    // run의 collector job 번호
    private UUID jobOf(UUID runId) {

        return (UUID) jdbcClient
                .sql("SELECT collector_job_id FROM scheduler.runs WHERE run_id = :runId")
                .param(
                        "runId",
                        runId
                )
                .query()
                .singleRow()
                .get("collector_job_id");
    }

    // run 상태
    private String stateOf(UUID runId) {

        return jdbcClient
                .sql("SELECT state FROM scheduler.runs WHERE run_id = :runId")
                .param(
                        "runId",
                        runId
                )
                .query(String.class)
                .single();
    }

    // 이 시험 클래스 전용 가짜 수신자
    private static FakePeers peers() {

        try {
            return new FakePeers();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
