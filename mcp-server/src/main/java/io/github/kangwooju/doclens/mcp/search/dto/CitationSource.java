package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/** 문서와 청크 source map에서 읽은 출처 값, Pack에 없는 값은 null */
public record CitationSource(
        String readerUrl,
        String sourceUrl,
        String originalUrl,
        String finalUrl,
        List<String> blockIds,
        List<SourcePointer> sourcePointer
) {

    /** 목록 불변 사본 */
    public CitationSource {

        blockIds = List
                .copyOf(blockIds);
        sourcePointer = List
                .copyOf(sourcePointer);
    }
}
