package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.UUID;

/** 새 Build 행 값, 상태는 항상 RUNNING */
public record NewBuild(
        UUID buildId,
        String jobKey,
        String packId,
        String sourceAttribution,
        String createdBy,
        String project,
        String exactVersion,
        String applicability,
        String runInputSnapshotHash,
        String inputScopeKey,
        String inputSnapshotHash,
        String processingConfigHash,
        String processingConfigJson,
        String parserPolicyVersion,
        String chunkerPolicyVersion,
        String sourceDigest,
        int documentCount
) {
}
