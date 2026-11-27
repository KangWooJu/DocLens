package io.github.kangwooju.doclens.pipeline.chunk.dto;

import java.util.List;

import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;

/**
 * 청크에 넣는 블록 전체나 조각
 * 조각이 아니면 charStart, charEnd, 행 범위는 null, 인라인 코드와 링크는 이 조각에 시작점이 든 것만
 */
public record ChunkPiece(
        RenderedBlock rendered,
        String text,
        int utf8Bytes,
        Integer charStart,
        Integer charEnd,
        boolean continuation,
        Integer rowStart,
        Integer rowEndExclusive,
        Integer totalRows,
        boolean headerRepeated,
        boolean oversized,
        List<InlineCode> inlineCodes,
        List<BlockLink> links
) {

    /** 목록 불변 사본 */
    public ChunkPiece {
        inlineCodes = List
                .copyOf(inlineCodes);
        links = List
                .copyOf(links);
    }
}
