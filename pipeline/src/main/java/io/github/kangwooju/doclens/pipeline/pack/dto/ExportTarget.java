package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.List;

/** Export 대상 Build와 Pack 메타의 원본 snapshot 목록, CHUNK 영수증 */
public record ExportTarget(
        BuildRow build,
        List<String> sourceSnapshotIds,
        StageDigest chunkReceipt
) {

    /** 목록 불변 사본 */
    public ExportTarget {
        sourceSnapshotIds = List
                .copyOf(sourceSnapshotIds);
    }
}
