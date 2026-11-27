package io.github.kangwooju.doclens.pipeline.global.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("트랜잭션 실행기")
class TransactionExecutorTests {

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("snapshot은 REPEATABLE READ 읽기 전용, 대기 상한 60초")
    void readsInRepeatableReadOnlySnapshot() {

        List<String> settings = transactionExecutor
                .readSnapshot(() -> List
                        .of(
                                show("transaction_isolation"),
                                show("transaction_read_only"),
                                show("idle_in_transaction_session_timeout")
                        ));

        assertEquals(
                List
                        .of(
                                "repeatable read",
                                "on",
                                "1min"
                        ),
                settings
        );
    }

    @Test
    @DisplayName("쓰기는 READ COMMITTED, 예외가 나면 rollback")
    void rollsBackWriteOnException() {

        assertEquals(
                "read committed",
                transactionExecutor
                        .write(() -> show("transaction_isolation"))
        );
        assertThrows(
                IllegalStateException.class,
                () -> transactionExecutor
                        .write(() -> {
                            jdbcClient
                                    .sql("CREATE TABLE pipeline.executor_rollback (value integer)")
                                    .update();

                            throw new IllegalStateException("rollback");
                        })
        );
        assertEquals(
                0,
                jdbcClient
                        .sql("SELECT count(*) FROM pg_tables WHERE schemaname = 'pipeline' AND tablename = 'executor_rollback'")
                        .query(Integer.class)
                        .single()
        );
    }

    // 현재 세션 설정 값
    private String show(String setting) {

        return jdbcClient
                .sql("SHOW " + setting)
                .query(String.class)
                .single();
    }
}
