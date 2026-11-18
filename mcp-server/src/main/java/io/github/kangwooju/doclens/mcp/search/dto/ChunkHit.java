package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/**
 * 검색 SQL이 돌려준 청크 한 행
 *
 * <p>lexicalScore는 FTS5 bm25 값(작을수록 관련), 정확 식별자로만 찾은 청크는 null
 */
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
        Double lexicalScore,
        boolean exactIdentifierMatch
) {

    public ChunkHit {

        headingPath = List
                .copyOf(headingPath);
    }
}
