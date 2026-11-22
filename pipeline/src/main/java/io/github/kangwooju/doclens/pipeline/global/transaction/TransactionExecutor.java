package io.github.kangwooju.doclens.pipeline.global.transaction;

import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 업무 쓰기와 Export snapshot 트랜잭션 실행
 * 쓰기는 READ COMMITTED, snapshot은 REPEATABLE READ 읽기 전용
 */
@Component
public class TransactionExecutor {

    /** snapshot 안 cursor 사이 대기 상한, 전역 15초보다 긴 Export 전용 값 */
    public static final String SNAPSHOT_IDLE_TIMEOUT = "60s";

    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate snapshotTemplate;
    private final JdbcClient jdbcClient;

    /** 트랜잭션 관리자에서 쓰기용과 snapshot용 template 생성 */
    public TransactionExecutor(
            PlatformTransactionManager transactionManager,
            JdbcClient jdbcClient
    ) {

        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.writeTemplate
                .setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.snapshotTemplate = new TransactionTemplate(transactionManager);
        this.snapshotTemplate
                .setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTemplate
                .setReadOnly(true);
        this.jdbcClient = jdbcClient;
    }

    /** READ COMMITTED 쓰기 트랜잭션 안의 작업 결과, 예외면 rollback */
    public <T> T write(Supplier<T> work) {

        return writeTemplate
                .execute(status -> work
                        .get());
    }

    /** REPEATABLE READ 읽기 전용 snapshot 안의 작업 결과, 첫 문장 전에 대기 상한 지정 */
    public <T> T readSnapshot(Supplier<T> work) {

        return snapshotTemplate
                .execute(status -> {
                    jdbcClient
                            .sql("SET LOCAL idle_in_transaction_session_timeout = '" + SNAPSHOT_IDLE_TIMEOUT + "'")
                            .update();

                    return work
                            .get();
                });
    }
}
