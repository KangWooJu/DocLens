package io.github.kangwooju.doclens.mcp.search.dto;

/** CHUNK 결과 속성, 지금은 BM25만 써서 vectorScore와 rrfScore는 null */
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
