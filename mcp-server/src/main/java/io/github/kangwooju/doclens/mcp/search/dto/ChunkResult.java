package io.github.kangwooju.doclens.mcp.search.dto;

import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;

/** search_docs 결과 하나 */
public record ChunkResult(
        String kind,
        String id,
        String text,
        Citation citation,
        ChunkAttributes attributes
) {

    /** 검색 행과 순위로 만든 결과 */
    public static ChunkResult from(
            ChunkHit hit,
            int rank,
            InstalledPack pack
    ) {

        return new ChunkResult(
                "CHUNK",
                hit
                        .chunkId(),
                hit
                        .content(),
                new Citation(
                        hit
                                .url(),
                        hit
                                .headingPath(),
                        hit
                                .anchor(),
                        pack
                                .packId(),
                        pack
                                .buildId(),
                        hit
                                .exactVersion(),
                        hit
                                .applicability(),
                        pack
                                .checksum()
                ),
                new ChunkAttributes(
                        hit
                                .documentId(),
                        hit
                                .sectionId(),
                        hit
                                .chunkId(),
                        rank,
                        hit
                                .lexicalScore(),
                        null,
                        null,
                        hit
                                .exactIdentifierMatch(),
                        "BM25"
                )
        );
    }
}
