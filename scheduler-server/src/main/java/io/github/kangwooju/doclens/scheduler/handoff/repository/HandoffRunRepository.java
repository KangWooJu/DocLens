package io.github.kangwooju.doclens.scheduler.handoff.repository;

import java.time.Duration;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import lombok.RequiredArgsConstructor;

/** 처리 단계 run SQL, PROCESSING 행만 옮김 */
@Repository
@RequiredArgsConstructor
public class HandoffRunRepository {

    private static final String RUN_COLUMNS = """
            run_id, schedule_key, delivery_state, collection_request, collector_job_id,
            ready_run_ref::text AS ready_run_ref, process_request, attempt_count, lease_until
            """;

    // 자기 lease일 때만 기록, lease를 잃었으면 0행
    private static final String OWNED = """
            WHERE run_id = :runId AND state = 'PROCESSING' AND lease_until = :leaseUntil
            """;

    private final JdbcClient jdbcClient;

    /** 시도 시각이 된 PROCESSING run 하나를 lease로 잡고 시도 횟수 증가 */
    public Optional<ClaimedRun> claim(Duration lease) {

        return jdbcClient
                .sql("""
                        UPDATE scheduler.runs
                        SET lease_until = now() + make_interval(secs => :leaseSeconds),
                            attempt_count = attempt_count + 1,
                            updated_at = now()
                        WHERE run_id = (
                            SELECT run_id
                            FROM scheduler.runs
                            WHERE state = 'PROCESSING'
                              AND next_attempt_at <= now()
                              AND (lease_until IS NULL OR lease_until < now())
                            ORDER BY next_attempt_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING
                        """ + RUN_COLUMNS)
                .param(
                        "leaseSeconds",
                        seconds(lease)
                )
                .query(ClaimedRun.class)
                .optional();
    }

    /** 보낼 처리 요청 본문 고정, 이미 고정됐거나 lease를 잃었으면 false */
    public boolean fixRequest(
            ClaimedRun run,
            String processRequest
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET process_request = :processRequest, updated_at = now()
                        """,
                run,
                " AND process_request IS NULL"
        )
                .param(
                        "processRequest",
                        processRequest
                )
                .update() == 1;
    }

    /** 처리와 발행 완료, receipt 기록 */
    public boolean markDone(
            ClaimedRun run,
            String result,
            String remoteState
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET state = 'DONE', delivery_state = 'SENT', result = CAST(:result AS jsonb),
                            remote_state = :remoteState, remote_checked_at = now(),
                            attempt_count = 0, lease_until = NULL, last_error = NULL, updated_at = now()
                        """,
                run,
                ""
        )
                .param(
                        "result",
                        result
                )
                .param(
                        "remoteState",
                        remoteState
                )
                .update() == 1;
    }

    /** 확정 실패, 원인에 따라 수집 단계나 처리 단계로 기록 */
    public boolean fail(
            ClaimedRun run,
            String failedStage,
            String failureKind,
            String remoteState,
            String lastError
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET state = 'FAILED', failed_stage = :failedStage, failure_kind = :failureKind,
                            remote_state = coalesce(:remoteState, remote_state), remote_checked_at = now(),
                            lease_until = NULL, last_error = :lastError, updated_at = now()
                        """,
                run,
                ""
        )
                .param(
                        "failedStage",
                        failedStage
                )
                .param(
                        "failureKind",
                        failureKind
                )
                .param(
                        "remoteState",
                        remoteState
                )
                .param(
                        "lastError",
                        lastError
                )
                .update() == 1;
    }

    /** 같은 본문 재전송까지 대기, lease 해제 */
    public boolean retryLater(
            ClaimedRun run,
            Duration delay,
            String lastError
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET next_attempt_at = now() + make_interval(secs => :delaySeconds),
                            lease_until = NULL, last_error = :lastError, updated_at = now()
                        """,
                run,
                ""
        )
                .param(
                        "delaySeconds",
                        seconds(delay)
                )
                .param(
                        "lastError",
                        lastError
                )
                .update() == 1;
    }

    // 자기 lease 조건과 추가 조건을 붙인 UPDATE 문장
    private JdbcClient.StatementSpec owned(
            String update,
            ClaimedRun run,
            String extraCondition
    ) {

        return jdbcClient
                .sql(update + OWNED + extraCondition)
                .param(
                        "runId",
                        run
                                .runId()
                )
                .param(
                        "leaseUntil",
                        run
                                .leaseUntil()
                );
    }

    // SQL interval용 초 단위 값
    private static double seconds(Duration duration) {

        return duration
                .toMillis() / 1000.0;
    }
}
