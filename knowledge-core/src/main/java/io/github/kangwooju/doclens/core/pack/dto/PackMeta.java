package io.github.kangwooju.doclens.core.pack.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.support.Require;

/**
 * Pack 메타데이터 입력
 * 스키마 버전, capabilities, logicalContentSha256은 Writer 기록 값
 */
public record PackMeta(
        String packId,
        String project,
        String docVersion,
        String buildId,
        String inputSnapshotHash,
        String processingConfigHash,
        String parserPolicyVersion,
        String chunkerPolicyVersion,
        String sourceAttribution,
        List<String> sourceSnapshotIds,
        String createdBy
) {

    /** 필수 값과 ID 형식 검사 */
    public PackMeta {

        Require
                .text(
                        packId,
                        "packId"
                );
        Require
                .text(
                        project,
                        "project"
                );
        Require
                .text(
                        docVersion,
                        "docVersion"
                );
        Require
                .text(
                        buildId,
                        "buildId"
                );
        Require
                .text(
                        inputSnapshotHash,
                        "inputSnapshotHash"
                );
        Require
                .text(
                        processingConfigHash,
                        "processingConfigHash"
                );
        Require
                .text(
                        parserPolicyVersion,
                        "parserPolicyVersion"
                );
        Require
                .text(
                        chunkerPolicyVersion,
                        "chunkerPolicyVersion"
                );
        Require
                .text(
                        sourceAttribution,
                        "sourceAttribution"
                );
        Require
                .text(
                        createdBy,
                        "createdBy"
                );
        sourceSnapshotIds = List
                .copyOf(sourceSnapshotIds);
    }
}
