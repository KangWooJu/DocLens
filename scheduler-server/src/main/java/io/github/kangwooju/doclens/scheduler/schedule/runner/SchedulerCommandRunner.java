package io.github.kangwooju.doclens.scheduler.schedule.runner;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.schedule.dto.CommandResult;
import io.github.kangwooju.doclens.scheduler.schedule.facade.ScheduleFacade;
import lombok.RequiredArgsConstructor;

/**
 * 운영자 명령 collect-now와 requeue
 * 결과 JSON 한 줄을 출력하고 종료 코드는 보관만 함
 */
@Component
@Profile("command")
@RequiredArgsConstructor
public class SchedulerCommandRunner implements ApplicationRunner, ExitCodeGenerator {

    // 인자 형식 오류 종료 코드
    private static final int USAGE_EXIT_CODE = 2;

    private final ScheduleFacade scheduleFacade;
    private final AtomicInteger exitCode = new AtomicInteger();

    /** 명령 하나 실행 */
    @Override
    public void run(ApplicationArguments arguments) {

        List<String> words = arguments
                .getNonOptionArgs();
        CommandResult result = words
                .size() == 2 ? execute(
                        words
                                .get(0),
                        words
                                .get(1),
                        arguments
                                .containsOption("fresh")
                ) : null;

        if (result == null) {
            System.err
                    .println("usage: collect-now <label> | requeue <runId> [--fresh]");
            exitCode
                    .set(USAGE_EXIT_CODE);

            return;
        }

        System.out
                .println(String
                        .format(
                                "{\"result\":\"%s\",\"runId\":%s}",
                                result
                                        .outcome(),
                                result
                                        .runId() == null ? "null" : "\"" + result
                                                .runId() + "\""
                        ));
        exitCode
                .set(result
                        .exitCode());
    }

    /** 보관한 명령 종료 코드 */
    @Override
    public int getExitCode() {

        return exitCode
                .get();
    }

    // 명령 이름별 실행, 모르는 명령이나 잘못된 run 번호면 null
    private CommandResult execute(
            String command,
            String argument,
            boolean fresh
    ) {

        if (command
                .equals("collect-now")) {
            return scheduleFacade
                    .collectNow(argument);
        }

        if (!command
                .equals("requeue")) {
            return null;
        }

        UUID runId;

        try {
            runId = UUID
                    .fromString(argument);
        } catch (IllegalArgumentException exception) {
            return null;
        }

        return scheduleFacade
                .requeue(
                        runId,
                        fresh
                );
    }
}
