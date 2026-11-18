package io.github.kangwooju.doclens.core.pack;

import java.util.List;

/**
 * Document Pack 메타데이터 입력
 *
 * <p>packType(DOCUMENT), packSchemaVersion(1), minReaderVersion(1),
 * capabilities, embedding(null), logicalContentSha256은 Writer가 채우는 값
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
