package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.time.Instant;
import java.util.UUID;

import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;

/** pipeline.builds 한 행, Export가 요청 없이 Pack 메타를 만드는 기준, 발행 번호와 시각은 SUCCEEDED에만 */
public record BuildRow(
        UUID buildId,
        String jobKey,
        String packId,
        String sourceAttribution,
        String createdBy,
        String project,
        String exactVersion,
        String inputSnapshotHash,
        String processingConfigHash,
        String parserPolicyVersion,
        String chunkerPolicyVersion,
        String sourceDigest,
        int documentCount,
        BuildStatus status,
        String failureReason,
        PackRecord pack,
        Long publicationSequence,
        Instant publishedAt
) {
}
