package io.github.kangwooju.doclens.mcp.search.dto;

/**
 * CHUNK 결과 속성(D16), 순서 B는 BM25만 써서 vectorScore·rrfScore는 null
 *
 * <p>blockIds는 Pack에 블록 ID가 없어 내지 않음(F19 공백)
 */
public record ChunkAttributes(
        String documentId,
        String sectionId,
        String chunkId,
        int rank,
        Double lexicalScore,
        Double vectorScore,
        Double rrfScore,
        boolean exactIdentifierMatch,
        String retrievalMode
) {
}
