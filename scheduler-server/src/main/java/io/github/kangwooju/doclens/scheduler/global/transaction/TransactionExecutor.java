package io.github.kangwooju.doclens.scheduler.global.transaction;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 요청 기록과 결과 기록의 짧은 쓰기 트랜잭션 실행, READ COMMITTED */
@Component
public class TransactionExecutor {

    private final TransactionTemplate writeTemplate;

    /** 트랜잭션 관리자에서 쓰기용 template 생성 */
    public TransactionExecutor(PlatformTransactionManager transactionManager) {

        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.writeTemplate
                .setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** READ COMMITTED 쓰기 트랜잭션 안의 작업 결과, 예외면 rollback */
    public <T> T write(Supplier<T> work) {

        return writeTemplate
                .execute(status -> work
                        .get());
    }
}
