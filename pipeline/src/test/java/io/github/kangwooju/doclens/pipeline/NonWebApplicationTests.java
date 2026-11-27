package io.github.kangwooju.doclens.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;

import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@Import(PostgresTestConfiguration.class)
@DisplayName("웹 없는 runner 실행")
class NonWebApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Security가 classpath에 있어도 웹 없는 문맥이 뜨고 내부 API filter chain은 만들지 않음")
    void startsWithoutWebSecurity() {

        assertEquals(
                0,
                context
                        .getBeanNamesForType(SecurityFilterChain.class).length
        );
    }
}
