package io.github.kangwooju.doclens.mcp.search.dto;

/** 청크 source map 항목 하나의 원본 위치, 조각이 아니면 charStart와 charEnd는 null */
public record SourcePointer(
        String blockId,
        String domPath,
        String elementId,
        String headingLocator,
        Integer blockOrdinal,
        Integer charStart,
        Integer charEnd
) {
}
