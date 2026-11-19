package io.github.kangwooju.doclens.pipeline.chunk.dto;

import io.github.kangwooju.doclens.pipeline.chunk.type.MemberRole;

/** 청크 구성원 하나와 역할 */
public record ChunkMember(
        ChunkPiece piece,
        MemberRole role
) {
}
