package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;

/**
 * 구조 블록 하나, 종류마다 쓰지 않는 필드는 null
 * text는 PARAGRAPH, CODE, LIST, ADMONITION의 본문, inlineCodes와 links의 위치는 text 기준
 */
public record Block(
        String blockId,
        BlockKind kind,
        int ordinal,
        String text,
        String language,
        String title,
        String admonitionType,
        TableData table,
        TabGroup tabGroup,
        List<InlineCode> inlineCodes,
        List<BlockLink> links,
        SourcePointer sourcePointer
) {

    /** 목록 불변 사본 */
    public Block {
        inlineCodes = List
                .copyOf(inlineCodes);
        links = List
                .copyOf(links);
    }
}
