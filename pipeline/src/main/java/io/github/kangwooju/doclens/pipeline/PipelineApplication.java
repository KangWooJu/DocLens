package io.github.kangwooju.doclens.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PipelineApplication {

    /** pipeline 실행 */
    public static void main(String[] args) {

        SpringApplication
                .run(
                        PipelineApplication.class,
                        args
                );
    }
}
