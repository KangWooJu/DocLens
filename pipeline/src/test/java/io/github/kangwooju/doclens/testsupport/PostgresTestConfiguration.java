package io.github.kangwooju.doclens.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 시험용 PostgreSQL 17 컨테이너 연결
 * pipeline component scan 밖 패키지라 자식 JVM 시험에서 자동으로 뜨지 않음
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    // 시험 컨텍스트가 공유하는 PostgreSQL 17 컨테이너
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {

        return new PostgreSQLContainer("postgres:17");
    }
}
