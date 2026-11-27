package io.github.kangwooju.doclens.scheduler.dispatch.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.handler.CollectorHandler;
import io.github.kangwooju.doclens.scheduler.dispatch.policy.DeliveryPolicy;
import io.github.kangwooju.doclens.scheduler.dispatch.repository.DispatchRunRepository;
import io.github.kangwooju.doclens.scheduler.dispatch.type.DeliveryState;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailureKind;
import io.github.kangwooju.doclens.scheduler.dispatch.type.ReplyKind;
import io.github.kangwooju.doclens.scheduler.global.config.dto.SchedulerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집 요청 전달과 job 상태 조회
 * 접수 전이면 고정한 요청을 다시 보내고, 접수 뒤면 같은 job 상태만 읽음
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchService {

    // collector job이 더 진행하지 않는 상태
    private static final Set<String> TERMINAL_JOB_STATES = Set
            .of(
                    "FAILED_FINAL",
                    "NEEDS_REVIEW",
                    "CANCELLED"
            );

    private final DispatchRunRepository dispatchRunRepository;
    private final CollectorHandler collectorHandler;
    private final DeliveryPolicy deliveryPolicy;
    private final SchedulerProperties properties;
    private final JsonMapper jsonMapper;

    /** 시도 시각이 된 수집 run 하나 */
    public Optional<ClaimedRun> claim() {

        return dispatchRunRepository
                .claim(properties
                        .lease());
    }

    /** 접수 전이면 요청 전송, 접수 뒤면 상태 조회, 트랜잭션 밖에서 호출 */
    public Delivery call(ClaimedRun run) {

        HttpReply reply = run
                .deliveryState() == DeliveryState.PENDING ? collectorHandler
                        .submit(run
                                .collectionRequest()) : collectorHandler
                                        .status(run
                                                .collectorJobId());

        return deliveryPolicy
                .classify(reply);
    }

    /** 응답 결과 기록, lease를 잃었으면 false */
    public boolean record(
            ClaimedRun run,
            Delivery delivery
    ) {

        return switch (delivery
                .kind()) {
            case OK -> run
                    .deliveryState() == DeliveryState.PENDING ? recordAccepted(
                            run,
                            delivery
                    ) : recordStatus(
                            run,
                            delivery
                    );
            case REJECTED -> dispatchRunRepository
                    .fail(
                            run,
                            FailureKind.REJECTED
                                    .name(),
                            delivery
                                    .remoteState(),
                            delivery
                                    .detail()
                    );
            case RETRY, AUTH -> retryLater(
                    run,
                    delivery
            );
        };
    }

    // 접수 응답의 job 번호와 상태
    private boolean recordAccepted(
            ClaimedRun run,
            Delivery delivery
    ) {

        JsonNode accept = jsonMapper
                .readTree(delivery
                        .reply()
                        .body());

        return dispatchRunRepository
                .markSent(
                        run,
                        UUID
                                .fromString(accept
                                        .get("jobId")
                                        .stringValue()),
                        accept
                                .get("state")
                                .stringValue(),
                        properties
                                .statusInterval()
                );
    }

    // job 상태에 따라 처리 단계로, 실패로, 또는 다음 조회까지 대기
    private boolean recordStatus(
            ClaimedRun run,
            Delivery delivery
    ) {

        JsonNode status = jsonMapper
                .readTree(delivery
                        .reply()
                        .body());
        String state = status
                .get("state")
                .stringValue();

        if (state
                .equals("SUCCEEDED")) {
            return dispatchRunRepository
                    .markCollected(
                            run,
                            jsonMapper
                                    .writeValueAsString(status
                                            .get("result")),
                            state
                    );
        }

        if (TERMINAL_JOB_STATES
                .contains(state)) {
            log
                    .warn(
                            "collector job {} of run {} ended as {}",
                            run
                                    .collectorJobId(),
                            run
                                    .runId(),
                            state
                    );

            return dispatchRunRepository
                    .fail(
                            run,
                            FailureKind.REMOTE_TERMINAL
                                    .name(),
                            state,
                            "collector job " + state + ", last error " + status
                                    .path("lastErrorCode")
                                    .asString(null)
                    );
        }

        return dispatchRunRepository
                .markSent(
                        run,
                        run
                                .collectorJobId(),
                        state,
                        properties
                                .statusInterval()
                );
    }

    // 같은 본문 재전송 또는 같은 job 재조회까지 대기
    private boolean retryLater(
            ClaimedRun run,
            Delivery delivery
    ) {

        if (delivery
                .kind() == ReplyKind.AUTH) {
            log
                    .warn(
                            "collector rejected the scheduler token for run {}: {}",
                            run
                                    .runId(),
                            delivery
                                    .detail()
                    );
        }

        return dispatchRunRepository
                .retryLater(
                        run,
                        deliveryPolicy
                                .nextDelay(
                                        run
                                                .attemptCount(),
                                        delivery
                                                .kind()
                                ),
                        delivery
                                .detail()
                );
    }
}
