package io.github.kangwooju.doclens.scheduler.dispatch.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import lombok.RequiredArgsConstructor;

/** 수집 단계 run SQL, COLLECTING 행만 옮김 */
@Repository
@RequiredArgsConstructor
public class DispatchRunRepository {

    private static final String RUN_COLUMNS = """
            run_id, schedule_key, delivery_state, collection_request, collector_job_id,
            ready_run_ref::text AS ready_run_ref, process_request, attempt_count, lease_until
            """;

    // 자기 lease일 때만 기록, lease를 잃었으면 0행
    private static final String OWNED = """
            WHERE run_id = :runId AND state = 'COLLECTING' AND lease_until = :leaseUntil
            """;

    private final JdbcClient jdbcClient;

    /** 시도 시각이 된 COLLECTING run 하나를 lease로 잡고 시도 횟수 증가 */
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
                            WHERE state = 'COLLECTING'
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

    /** 접수 확인 또는 진행 중 조회 결과, 다음 조회 시각까지 대기 */
    public boolean markSent(
            ClaimedRun run,
            UUID jobId,
            String remoteState,
            Duration statusInterval
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET delivery_state = 'SENT', collector_job_id = :jobId,
                            remote_state = :remoteState, remote_checked_at = now(),
                            attempt_count = 0, next_attempt_at = now() + make_interval(secs => :delaySeconds),
                            lease_until = NULL, last_error = NULL, updated_at = now()
                        """,
                run
        )
                .param(
                        "jobId",
                        jobId
                )
                .param(
                        "remoteState",
                        remoteState
                )
                .param(
                        "delaySeconds",
                        seconds(statusInterval)
                )
                .update() == 1;
    }

    /** 수집 job 성공, readyRun 참조를 남기고 처리 단계로 */
    public boolean markCollected(
            ClaimedRun run,
            String readyRunRef,
            String remoteState
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET state = 'PROCESSING', delivery_state = 'PENDING',
                            ready_run_ref = CAST(:readyRunRef AS jsonb),
                            remote_state = :remoteState, remote_checked_at = now(),
                            attempt_count = 0, next_attempt_at = now(),
                            lease_until = NULL, last_error = NULL, updated_at = now()
                        """,
                run
        )
                .param(
                        "readyRunRef",
                        readyRunRef
                )
                .param(
                        "remoteState",
                        remoteState
                )
                .update() == 1;
    }

    /** 수집 단계 확정 실패, collector job 번호는 남김 */
    public boolean fail(
            ClaimedRun run,
            String failureKind,
            String remoteState,
            String lastError
    ) {

        return owned(
                """
                        UPDATE scheduler.runs
                        SET state = 'FAILED', failed_stage = 'COLLECTING', failure_kind = :failureKind,
                            remote_state = :remoteState, remote_checked_at = now(),
                            lease_until = NULL, last_error = :lastError, updated_at = now()
                        """,
                run
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
                run
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

    // 자기 lease 조건을 붙인 UPDATE 문장
    private JdbcClient.StatementSpec owned(
            String update,
            ClaimedRun run
    ) {

        return jdbcClient
                .sql(update + OWNED)
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
