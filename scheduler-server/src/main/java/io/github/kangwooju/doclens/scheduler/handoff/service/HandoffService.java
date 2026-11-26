package io.github.kangwooju.doclens.scheduler.handoff.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.scheduler.dispatch.dto.ClaimedRun;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.Delivery;
import io.github.kangwooju.doclens.scheduler.dispatch.dto.HttpReply;
import io.github.kangwooju.doclens.scheduler.dispatch.handler.CollectorHandler;
import io.github.kangwooju.doclens.scheduler.dispatch.policy.DeliveryPolicy;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailedStage;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailureKind;
import io.github.kangwooju.doclens.scheduler.dispatch.type.ReplyKind;
import io.github.kangwooju.doclens.scheduler.global.config.dto.SchedulerProperties;
import io.github.kangwooju.doclens.scheduler.handoff.converter.ProcessRequestConverter;
import io.github.kangwooju.doclens.scheduler.handoff.dto.PackScope;
import io.github.kangwooju.doclens.scheduler.handoff.dto.Preparation;
import io.github.kangwooju.doclens.scheduler.handoff.handler.PipelineHandler;
import io.github.kangwooju.doclens.scheduler.handoff.repository.HandoffRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집 결과의 처리 인계
 * readyRun bytes 대조, 현재 발행 번호 조회, 처리 요청 고정과 동기 전송
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffService {

    // 같은 readyRun은 같은 Build 키라 다시 보내도 같은 결과, 새 수집이 필요한 거절 code
    private static final Set<String> INPUT_REJECTIONS = Set
            .of(
                    "INPUT_INCOMPLETE",
                    "HASH_MISMATCH"
            );

    private final HandoffRunRepository handoffRunRepository;
    private final CollectorHandler collectorHandler;
    private final PipelineHandler pipelineHandler;
    private final DeliveryPolicy deliveryPolicy;
    private final ProcessRequestConverter processRequestConverter;
    private final SchedulerProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** 시도 시각이 된 처리 run 하나 */
    public Optional<ClaimedRun> claim() {

        return handoffRunRepository
                .claim(properties
                        .lease());
    }

    /** readyRun bytes 대조와 발행 번호 조회로 만든 처리 요청, 트랜잭션 밖에서 호출 */
    public Preparation prepare(ClaimedRun run) {

        HttpReply readyRun = collectorHandler
                .readyRun(run
                        .collectorJobId());
        Delivery readyRunDelivery = deliveryPolicy
                .classify(readyRun);

        if (readyRunDelivery
                .kind() != ReplyKind.OK) {
            return Preparation
                    .failed(
                            readyRunDelivery,
                            FailedStage.COLLECTING,
                            FailureKind.REJECTED
                    );
        }

        if (!matchesReference(
                run
                        .readyRunRef(),
                readyRun
                        .body()
        )) {
            return Preparation
                    .failed(
                            new Delivery(
                                    readyRun,
                                    ReplyKind.REJECTED,
                                    null,
                                    "readyRun bytes differ from the sealed objectRef hash or size"
                            ),
                            FailedStage.COLLECTING,
                            FailureKind.INTEGRITY
                    );
        }

        PackScope scope = processRequestConverter
                .packScope(readyRun
                        .body());
        HttpReply latest = pipelineHandler
                .latestPublication(scope);
        Delivery latestDelivery = deliveryPolicy
                .classify(latest);
        boolean unpublished = latest
                .status() == 404 && "NOT_FOUND"
                        .equals(latestDelivery
                                .errorCode());

        if (!unpublished && latestDelivery
                .kind() != ReplyKind.OK) {
            return Preparation
                    .failed(
                            latestDelivery,
                            FailedStage.PROCESSING,
                            FailureKind.REJECTED
                    );
        }

        long expectedSequence = unpublished ? 0 : jsonMapper
                .readTree(latest
                        .body())
                .get("publicationSequence")
                .longValue();

        return Preparation
                .ready(processRequestConverter
                        .toProcessRequest(
                                readyRun
                                        .body(),
                                UUID
                                        .randomUUID(),
                                UUID
                                        .randomUUID(),
                                properties
                                        .processingConfigHash(),
                                expectedSequence,
                                clock
                                        .instant()
                        ));
    }

    /** 처리 요청 본문을 행에 고정, 이미 고정됐거나 lease를 잃었으면 false */
    public boolean fix(
            ClaimedRun run,
            String processRequest
    ) {

        return handoffRunRepository
                .fixRequest(
                        run,
                        processRequest
                );
    }

    /** 고정한 본문의 동기 처리 요청, 트랜잭션 밖에서 호출 */
    public Delivery call(String processRequest) {

        return deliveryPolicy
                .classify(pipelineHandler
                        .process(processRequest));
    }

    /** 준비 실패 기록, lease를 잃었으면 false */
    public boolean recordPreparation(
            ClaimedRun run,
            Preparation preparation
    ) {

        Delivery failure = preparation
                .failure();

        if (failure
                .kind() == ReplyKind.REJECTED) {
            return fail(
                    run,
                    preparation
                            .failedStage(),
                    preparation
                            .failureKind(),
                    failure
            );
        }

        return retryLater(
                run,
                failure
        );
    }

    /** 처리 응답 기록, lease를 잃었으면 false */
    public boolean record(
            ClaimedRun run,
            Delivery delivery
    ) {

        return switch (delivery
                .kind()) {
            case OK -> handoffRunRepository
                    .markDone(
                            run,
                            delivery
                                    .reply()
                                    .body(),
                            jsonMapper
                                    .readTree(delivery
                                            .reply()
                                            .body())
                                    .path("projectionResults")
                                    .path(0)
                                    .path("state")
                                    .asString("SUCCEEDED")
                    );
            case REJECTED -> fail(
                    run,
                    rejectedStage(delivery),
                    FailureKind.REJECTED,
                    delivery
            );
            case RETRY, AUTH -> retryLater(
                    run,
                    delivery
            );
        };
    }

    // 수집 결과가 원인인 거절은 수집 단계, 설정과 코드가 원인인 거절은 처리 단계
    private static FailedStage rejectedStage(Delivery delivery) {

        boolean inputRejected = delivery
                .reply()
                .status() == 422 && INPUT_REJECTIONS
                        .contains(delivery
                                .errorCode());

        return inputRejected ? FailedStage.COLLECTING : FailedStage.PROCESSING;
    }

    // 확정 실패 기록
    private boolean fail(
            ClaimedRun run,
            FailedStage failedStage,
            FailureKind failureKind,
            Delivery delivery
    ) {

        log
                .warn(
                        "run {} failed at {} ({}): {}",
                        run
                                .runId(),
                        failedStage,
                        failureKind,
                        delivery
                                .detail()
                );

        return handoffRunRepository
                .fail(
                        run,
                        failedStage
                                .name(),
                        failureKind
                                .name(),
                        failureKind == FailureKind.INTEGRITY ? null : delivery
                                .remoteState(),
                        delivery
                                .detail()
                );
    }

    // 같은 본문 재전송까지 대기
    private boolean retryLater(
            ClaimedRun run,
            Delivery delivery
    ) {

        if (delivery
                .kind() == ReplyKind.AUTH) {
            log
                    .warn(
                            "a receiver rejected the scheduler token for run {}: {}",
                            run
                                    .runId(),
                            delivery
                                    .detail()
                    );
        }

        return handoffRunRepository
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

    // 받은 bytes의 sha256과 크기가 job 상태의 objectRef와 같은지
    private boolean matchesReference(
            String readyRunRef,
            String body
    ) {

        JsonNode reference = jsonMapper
                .readTree(readyRunRef);
        byte[] bytes = body
                .getBytes(StandardCharsets.UTF_8);

        return reference
                .get("sizeBytes")
                .longValue() == bytes.length
                && reference
                        .get("contentHash")
                        .stringValue()
                        .equals("sha256:" + sha256(bytes));
    }

    // 소문자 hex sha256
    private static String sha256(byte[] bytes) {

        try {
            return HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}
