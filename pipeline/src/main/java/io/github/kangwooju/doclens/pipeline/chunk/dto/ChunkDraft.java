package io.github.kangwooju.doclens.pipeline.chunk.dto;

import java.util.List;

/** packing이 정한 청크 하나의 구성원, 순서대로 content가 됨 */
public record ChunkDraft(
        List<ChunkMember> members
) {

    /** 구성원 목록 불변 사본 */
    public ChunkDraft {
        members = List
                .copyOf(members);
    }
}
