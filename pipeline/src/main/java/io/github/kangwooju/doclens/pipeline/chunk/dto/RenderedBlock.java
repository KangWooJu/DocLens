package io.github.kangwooju.doclens.pipeline.chunk.dto;

import java.util.List;

import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;

/**
 * 청크 content에 들어갈 블록 텍스트와 위치
 * tableHeader는 표 caption과 header 줄, units는 목록과 표의 분할 단위, 위치는 text 기준 code point
 */
public record RenderedBlock(
        Block block,
        String text,
        int utf8Bytes,
        String tableHeader,
        int totalRows,
        List<RenderUnit> units,
        List<InlineCode> inlineCodes,
        List<BlockLink> links
) {

    /** 목록 불변 사본 */
    public RenderedBlock {
        units = List
                .copyOf(units);
        inlineCodes = List
                .copyOf(inlineCodes);
        links = List
                .copyOf(links);
    }
}
