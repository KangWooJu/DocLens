package io.github.kangwooju.doclens.scheduler.schedule.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.kangwooju.doclens.scheduler.schedule.dto.FailedRun;
import io.github.kangwooju.doclens.scheduler.schedule.dto.NewRun;
import lombok.RequiredArgsConstructor;

/** run 행 삽입과 실패 run 되돌림 SQL */
@Repository
@RequiredArgsConstructor
public class ScheduleRunRepository {

    // 되돌림 공통 열, 실패 열과 시도 기록을 비우고 바로 시도
    private static final String RESET = """
            failed_stage = NULL, failure_kind = NULL, attempt_count = 0, next_attempt_at = now(),
            lease_until = NULL, last_error = NULL, updated_at = now()
            WHERE run_id = :runId AND state = 'FAILED'
            """;

    private final JdbcClient jdbcClient;

    /** 새 run, 같은 slot이나 다른 활성 run이 있으면 빈 값 */
    public Optional<UUID> insert(NewRun run) {

        return jdbcClient
                .sql("""
                        INSERT INTO scheduler.runs (run_id, schedule_key, slot_key, collection_request)
                        VALUES (:runId, :scheduleKey, :slotKey, :collectionRequest)
                        ON CONFLICT DO NOTHING
                        RETURNING run_id
                        """)
                .paramSource(run)
                .query(UUID.class)
                .optional();
    }

    /** 같은 일정의 slot run 번호 */
    public Optional<UUID> findRunId(
            String scheduleKey,
            String slotKey
    ) {

        return jdbcClient
                .sql("""
                        SELECT run_id
                        FROM scheduler.runs
                        WHERE schedule_key = :scheduleKey AND slot_key = :slotKey
                        """)
                .param(
                        "scheduleKey",
                        scheduleKey
                )
                .param(
                        "slotKey",
                        slotKey
                )
                .query(UUID.class)
                .optional();
    }

    /** 같은 일정에 수집이나 처리 중인 run이 있는지 */
    public boolean activeRunExists(String scheduleKey) {

        return jdbcClient
                .sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM scheduler.runs
                            WHERE schedule_key = :scheduleKey AND state IN ('COLLECTING', 'PROCESSING'))
                        """)
                .param(
                        "scheduleKey",
                        scheduleKey
                )
                .query(Boolean.class)
                .single();
    }

    /** requeue 대상 run을 행 잠금으로 읽기 */
    public Optional<FailedRun> lockRun(UUID runId) {

        return jdbcClient
                .sql("""
                        SELECT run_id, schedule_key, state, failed_stage, failure_kind
                        FROM scheduler.runs
                        WHERE run_id = :runId
                        FOR UPDATE
                        """)
                .param(
                        "runId",
                        runId
                )
                .query(FailedRun.class)
                .optional();
    }

    /** 같은 일정에 이 run보다 늦게 만든 DONE run이 있는지 */
    public boolean laterDoneExists(UUID runId) {

        return jdbcClient
                .sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM scheduler.runs later
                            JOIN scheduler.runs failed ON failed.run_id = :runId
                            WHERE later.schedule_key = failed.schedule_key
                              AND later.state = 'DONE'
                              AND later.created_at > failed.created_at)
                        """)
                .param(
                        "runId",
                        runId
                )
                .query(Boolean.class)
                .single();
    }

    /** 새 requestId 요청으로 수집부터 다시 */
    public boolean requeueCollection(
            UUID runId,
            String collectionRequest
    ) {

        return jdbcClient
                .sql("""
                        UPDATE scheduler.runs
                        SET state = 'COLLECTING', delivery_state = 'PENDING', collection_request = :collectionRequest,
                            collector_job_id = NULL, ready_run_ref = NULL, process_request = NULL,
                            remote_state = NULL, remote_checked_at = NULL,
                        """ + RESET)
                .param(
                        "runId",
                        runId
                )
                .param(
                        "collectionRequest",
                        collectionRequest
                )
                .update() == 1;
    }

    /** 같은 collector job을 다시 따라감, 새 요청 없음 */
    public boolean followJob(UUID runId) {

        return jdbcClient
                .sql("""
                        UPDATE scheduler.runs
                        SET state = 'COLLECTING', delivery_state = 'SENT', ready_run_ref = NULL, process_request = NULL,
                        """ + RESET)
                .param(
                        "runId",
                        runId
                )
                .update() == 1;
    }

    /** 같은 readyRun으로 처리 요청을 새로 만들도록 처리 단계로 */
    public boolean requeueProcessing(UUID runId) {

        return jdbcClient
                .sql("""
                        UPDATE scheduler.runs
                        SET state = 'PROCESSING', delivery_state = 'PENDING', process_request = NULL,
                        """ + RESET)
                .param(
                        "runId",
                        runId
                )
                .update() == 1;
    }
}
