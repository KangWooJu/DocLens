package io.github.kangwooju.doclens.scheduler.schedule.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.scheduler.dispatch.trigger.DispatchTrigger;
import io.github.kangwooju.doclens.testsupport.FakePeers;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest(args = {
        "collect-now",
        "cli-1"
})
@ActiveProfiles("command")
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("운영자 명령 profile")
class SchedulerCommandRunnerTests {

    @Autowired
    private SchedulerCommandRunner runner;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcClient jdbcClient;

    // 요청 틀 파일
    @DynamicPropertySource
    static void requestFile(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.scheduler.request-file",
                        FakePeers::requestTemplateFile
                );
    }

    @Test
    @DisplayName("기동 인자 명령 실행, 웹 서버와 일정 없음, 종료 코드는 ExitCodeGenerator로")
    void runsTheStartupCommand(CapturedOutput output) {

        assertFalse(context instanceof WebServerApplicationContext);
        assertTrue(context
                .getBeansOfType(DispatchTrigger.class)
                .isEmpty());
        assertEquals(
                0,
                runner
                        .getExitCode()
        );
        assertEquals(
                1,
                jdbcClient
                        .sql("SELECT count(*) FROM scheduler.runs WHERE slot_key = 'manual:cli-1'")
                        .query(Integer.class)
                        .single()
        );
        assertTrue(output
                .getOut()
                .contains("{\"result\":\"CREATED\",\"runId\":\""));
    }

    @Test
    @DisplayName("결과별 종료 코드, 같은 label 0, 다른 활성 run 3, FAILED 아님 4, 인자 오류 2")
    void mapsOutcomesToExitCodes(CapturedOutput output) {

        assertExit(
                0,
                "collect-now",
                "cli-1"
        );
        assertTrue(output
                .getOut()
                .contains("{\"result\":\"DUPLICATE_SLOT\""));
        assertExit(
                3,
                "collect-now",
                "cli-2"
        );
        assertTrue(output
                .getOut()
                .contains("{\"result\":\"ACTIVE_RUN_EXISTS\",\"runId\":null}"));
        assertExit(
                4,
                "requeue",
                UUID
                        .randomUUID()
                        .toString()
        );
        assertExit(
                2,
                "requeue",
                "not-a-run"
        );
        assertExit(
                2,
                "unknown"
        );
    }

    // 명령 하나 실행 뒤 종료 코드 확인
    private void assertExit(
            int expected,
            String... arguments
    ) {

        runner
                .run(new DefaultApplicationArguments(arguments));

        assertEquals(
                expected,
                runner
                        .getExitCode()
        );
    }
}
