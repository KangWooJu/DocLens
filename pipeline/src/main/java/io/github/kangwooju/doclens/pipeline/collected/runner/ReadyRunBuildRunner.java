package io.github.kangwooju.doclens.pipeline.collected.runner;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;

/** doclens.pipeline.ready-run.key가 있을 때만, readyRun 하나를 Build와 업로드까지 하고 끝나는 진입점 */
@Component
@ConditionalOnProperty("doclens.pipeline.ready-run.key")
public class ReadyRunBuildRunner implements ApplicationRunner {

    private static final Logger LOGGER = System
            .getLogger(ReadyRunBuildRunner.class
                    .getName());

    private final PackBuildFacade packBuildFacade;
    private final ObjectRef readyRun;

    /** collector status.result의 key, hash, 크기 */
    public ReadyRunBuildRunner(
            PackBuildFacade packBuildFacade,
            @Value("${doclens.pipeline.ready-run.key}") String key,
            @Value("${doclens.pipeline.ready-run.hash}") String hash,
            @Value("${doclens.pipeline.ready-run.size}") long size
    ) {

        this.packBuildFacade = packBuildFacade;
        this.readyRun = new ObjectRef(
                key,
                hash,
                size
        );
    }

    /** projection마다 결과를 로그로 남기고 SUCCEEDED가 아니라 Pack이 없는 결과가 있으면 IllegalStateException */
    @Override
    public void run(ApplicationArguments arguments) {

        List<BuildResult> results = packBuildFacade
                .buildCollected(readyRun);

        for (BuildResult result : results) {
            LOGGER
                    .log(
                            Level.INFO,
                            "build {0} {1} pack {2}",
                            result
                                    .buildId(),
                            result
                                    .status(),
                            result
                                    .pack()
                    );
        }

        if (results
                .stream()
                .anyMatch(result -> result
                        .pack() == null)) {
            throw new IllegalStateException("collected run " + readyRun
                    .objectKey() + " did not build: " + results);
        }
    }
}
