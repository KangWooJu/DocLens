package io.github.kangwooju.doclens.testsupport;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.kangwooju.doclens.pipeline.PipelineApplication;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;

/** kill 뒤 Export 시험의 자식 JVM, ingest만 하고 결과 한 줄을 쓴 뒤 kill을 기다림 */
public final class IngestThenWaitMain {

    /** 결과 줄 접두어 */
    public static final String PREFIX = "INGESTED ";

    // 인스턴스 생성 금지
    private IngestThenWaitMain() {
    }

    /** Spring 인자로 DB와 object root를 받아 ingest 한 번 */
    public static void main(String[] args) throws InterruptedException {

        ConfigurableApplicationContext context = new SpringApplicationBuilder(PipelineApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        String line;

        try {
            BuildResult result = context
                    .getBean(PackBuildFacade.class)
                    .ingest(PackRequestFixtures
                            .request(PackRequestFixtures
                                    .sources()));

            line = PREFIX + result
                    .status() + " " + result
                            .buildId() + " " + result
                                    .processingConfigHash();
        } catch (RuntimeException exception) {
            line = PREFIX + "ERROR " + exception;
        }

        System.out
                .println(line);
        System.out
                .flush();
        Thread
                .sleep(Long.MAX_VALUE);
    }
}
