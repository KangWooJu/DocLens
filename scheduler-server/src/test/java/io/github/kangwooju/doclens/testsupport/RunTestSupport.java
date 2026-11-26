package io.github.kangwooju.doclens.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.scheduler.dispatch.facade.DispatchFacade;
import io.github.kangwooju.doclens.scheduler.handoff.facade.HandoffFacade;
import io.github.kangwooju.doclens.scheduler.schedule.dto.CommandResult;
import io.github.kangwooju.doclens.scheduler.schedule.facade.ScheduleFacade;

/**
 * 가짜 수신자와 PostgreSQL로 run을 한 단계씩 움직이는 시험 기반
 * 일정은 시험 동안 돌지 않고 시험이 Facade를 직접 부름
 */
@SpringBootTest(properties = {
        "doclens.scheduler.initial-delay=1d",
        "doclens.scheduler.process-read-timeout=1s",
        "doclens.scheduler.token=" + FakePeers.TOKEN,
        "doclens.scheduler.processing-config-hash=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
})
@Import(PostgresTestConfiguration.class)
public abstract class RunTestSupport {

    // 시험 클래스가 공유하는 가짜 수신자
    protected static final FakePeers PEERS = peers();

    @Autowired
    protected ScheduleFacade scheduleFacade;

    @Autowired
    protected DispatchFacade dispatchFacade;

    @Autowired
    protected HandoffFacade handoffFacade;

    @Autowired
    protected JdbcClient jdbcClient;

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

    // 시험마다 run 표와 가짜 수신자 비우기
    @BeforeEach
    void cleanRuns() {

        jdbcClient
                .sql("TRUNCATE scheduler.runs")
                .update();
        PEERS
                .reset();
    }

    // 지금 수집 run 하나
    protected UUID collect(String label) {

        CommandResult result = scheduleFacade
                .collectNow(label);

        return result
                .runId();
    }

    // 모든 run의 다음 시도 시각을 지금으로 당긴 뒤 수집 단계 한 번
    protected boolean dispatch() {

        makeDue();

        return dispatchFacade
                .dispatchNext();
    }

    // 모든 run의 다음 시도 시각을 지금으로 당긴 뒤 처리 단계 한 번
    protected boolean handoff() {

        makeDue();

        return handoffFacade
                .handoffNext();
    }

    // run 한 행의 열
    protected Map<String, Object> row(UUID runId) {

        return jdbcClient
                .sql("SELECT * FROM scheduler.runs WHERE run_id = :runId")
                .param(
                        "runId",
                        runId
                )
                .query()
                .singleRow();
    }

    // run의 collector job 번호
    protected UUID jobId(UUID runId) {

        return (UUID) row(runId)
                .get("collector_job_id");
    }

    // 다음 시도 시각을 지금으로
    protected void makeDue() {

        jdbcClient
                .sql("UPDATE scheduler.runs SET next_attempt_at = now()")
                .update();
    }

    // JVM 동안 유지하는 가짜 수신자
    private static FakePeers peers() {

        try {
            return new FakePeers();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
