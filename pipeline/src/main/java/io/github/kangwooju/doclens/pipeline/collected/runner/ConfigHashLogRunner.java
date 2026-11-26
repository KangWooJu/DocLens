package io.github.kangwooju.doclens.pipeline.collected.runner;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;
import lombok.RequiredArgsConstructor;

/** 기동 때 현재 처리 설정 hash 한 줄, 운영자가 scheduler 설정으로 옮기는 값 */
@Component
@RequiredArgsConstructor
public class ConfigHashLogRunner implements ApplicationRunner {

    private static final Logger LOGGER = System
            .getLogger(ConfigHashLogRunner.class
                    .getName());

    private final PackBuildFacade packBuildFacade;

    /** processing config sha256 hex를 INFO로 기록 */
    @Override
    public void run(ApplicationArguments arguments) {

        LOGGER
                .log(
                        Level.INFO,
                        "processing config {0}",
                        packBuildFacade
                                .processingConfigHash()
                );
    }
}
