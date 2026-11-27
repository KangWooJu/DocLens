package io.github.kangwooju.doclens.scheduler.schedule.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.scheduler.dispatch.type.FailedStage;
import io.github.kangwooju.doclens.scheduler.dispatch.type.FailureKind;
import io.github.kangwooju.doclens.scheduler.global.config.dto.SchedulerProperties;
import io.github.kangwooju.doclens.scheduler.schedule.converter.CollectionRequestConverter;
import io.github.kangwooju.doclens.scheduler.schedule.dto.CommandResult;
import io.github.kangwooju.doclens.scheduler.schedule.dto.FailedRun;
import io.github.kangwooju.doclens.scheduler.schedule.dto.NewRun;
import io.github.kangwooju.doclens.scheduler.schedule.repository.ScheduleRunRepository;
import io.github.kangwooju.doclens.scheduler.schedule.type.CommandOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * run 등록과 실패 run 되돌림
 * slot 하나는 행 하나, 일정마다 활성 run 하나
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    // 매일 slot 날짜 기준 시간대
    private static final ZoneId SLOT_ZONE = ZoneId
            .of("Asia/Seoul");

    private final ScheduleRunRepository scheduleRunRepository;
    private final CollectionRequestConverter collectionRequestConverter;
    private final SchedulerProperties properties;
    private final Clock clock;

    /** 일정이 켜져 있고 오늘 수집 시각이 지났으면 오늘 slot 키 */
    public Optional<String> dueSlot() {

        SchedulerProperties.Collect collect = properties
                .collect();

        if (!collect
                .enabled()) {
            return Optional
                    .empty();
        }

        ZonedDateTime now = ZonedDateTime
                .now(clock
                        .withZone(SLOT_ZONE));

        if (now
                .toLocalTime()
                .isBefore(LocalTime
                        .parse(collect
                                .time()))) {
            return Optional
                    .empty();
        }

        return Optional
                .of("daily:" + now
                        .toLocalDate());
    }

    /** slot 하나의 run 행, 같은 slot이 있거나 다른 활성 run이 있으면 만들지 않음 */
    public CommandResult register(String slotKey) {

        String scheduleKey = properties
                .scheduleKey();
        Optional<UUID> existing = scheduleRunRepository
                .findRunId(
                        scheduleKey,
                        slotKey
                );

        if (existing
                .isPresent()) {
            return CommandResult
                    .of(
                            CommandOutcome.DUPLICATE_SLOT,
                            existing
                                    .get()
                    );
        }

        if (scheduleRunRepository
                .activeRunExists(scheduleKey)) {
            return CommandResult
                    .of(
                            CommandOutcome.ACTIVE_RUN_EXISTS,
                            null
                    );
        }

        UUID runId = UUID
                .randomUUID();
        Optional<UUID> inserted = scheduleRunRepository
                .insert(new NewRun(
                        runId,
                        scheduleKey,
                        slotKey,
                        renderRequest()
                ));

        if (inserted
                .isPresent()) {
            log
                    .info(
                            "registered run {} for slot {}",
                            runId,
                            slotKey
                    );

            return CommandResult
                    .of(
                            CommandOutcome.CREATED,
                            runId
                    );
        }

        return scheduleRunRepository
                .findRunId(
                        scheduleKey,
                        slotKey
                )
                .map(id -> CommandResult
                        .of(
                                CommandOutcome.DUPLICATE_SLOT,
                                id
                        ))
                .orElseGet(() -> CommandResult
                        .of(
                                CommandOutcome.ACTIVE_RUN_EXISTS,
                                null
                        ));
    }

    /** FAILED run을 실패 단계로 되돌림, fresh면 수집 실패도 새 요청으로 */
    public CommandResult requeue(
            UUID runId,
            boolean fresh
    ) {

        Optional<FailedRun> locked = scheduleRunRepository
                .lockRun(runId);

        if (locked
                .isEmpty() || !locked
                        .get()
                        .state()
                        .equals("FAILED")) {
            return CommandResult
                    .of(
                            CommandOutcome.NOT_FAILED,
                            runId
                    );
        }

        FailedRun run = locked
                .get();

        if (scheduleRunRepository
                .activeRunExists(run
                        .scheduleKey())) {
            return CommandResult
                    .of(
                            CommandOutcome.ACTIVE_RUN_EXISTS,
                            runId
                    );
        }

        if (run
                .failedStage() == FailedStage.PROCESSING) {
            if (scheduleRunRepository
                    .laterDoneExists(runId)) {
                return CommandResult
                        .of(
                                CommandOutcome.SUPERSEDED,
                                runId
                        );
            }

            scheduleRunRepository
                    .requeueProcessing(runId);
        } else if (run
                .failureKind() == FailureKind.REMOTE_TERMINAL && !fresh) {
            scheduleRunRepository
                    .followJob(runId);
        } else {
            scheduleRunRepository
                    .requeueCollection(
                            runId,
                            renderRequest()
                    );
        }

        log
                .info(
                        "requeued run {} from {} ({}), fresh {}",
                        runId,
                        run
                                .failedStage(),
                        run
                                .failureKind(),
                        fresh
                );

        return CommandResult
                .of(
                        CommandOutcome.REQUEUED,
                        runId
                );
    }

    // 요청 틀 파일에 새 식별자와 요청 시각을 채운 본문
    private String renderRequest() {

        try {
            return collectionRequestConverter
                    .render(
                            Files
                                    .readString(properties
                                            .requestFile()),
                            UUID
                                    .randomUUID(),
                            UUID
                                    .randomUUID(),
                            clock
                                    .instant()
                    );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "cannot read the collection request template " + properties
                            .requestFile(),
                    exception
            );
        }
    }
}
