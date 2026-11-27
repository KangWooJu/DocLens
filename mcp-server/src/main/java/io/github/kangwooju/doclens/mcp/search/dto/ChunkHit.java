package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/** 검색 SQL 결과 한 행, lexicalScore는 bm25 값(식별자로만 찾은 행은 null) */
public record ChunkHit(
        String chunkId,
        String documentId,
        String sectionId,
        String content,
        String url,
        String applicability,
        String exactVersion,
        String anchor,
        List<String> headingPath,
        CitationSource source,
        Double lexicalScore,
        boolean exactIdentifierMatch
) {

    /** headingPath 불변 사본 */
    public ChunkHit {

        headingPath = List
                .copyOf(headingPath);
    }
}
