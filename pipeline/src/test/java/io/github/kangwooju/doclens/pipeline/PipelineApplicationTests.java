package io.github.kangwooju.doclens.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("pipeline 애플리케이션")
class PipelineApplicationTests {

    @Test
    @DisplayName("스프링 애플리케이션 컨텍스트 로딩")
    void contextLoads() {
    }
}
