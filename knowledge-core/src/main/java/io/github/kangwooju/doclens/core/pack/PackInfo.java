package io.github.kangwooju.doclens.core.pack;

import java.util.List;

/** 검증을 통과한 Pack의 메타데이터와 규모 */
public record PackInfo(
        String packId,
        String project,
        String docVersion,
        String buildId,
        int packSchemaVersion,
        int minReaderVersion,
        String logicalContentSha256,
        List<String> capabilities,
        long documentCount,
        long chunkCount
) {

    public PackInfo {

        capabilities = List
                .copyOf(capabilities);
    }
}
