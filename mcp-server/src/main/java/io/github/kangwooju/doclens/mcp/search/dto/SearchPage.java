package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;

/**
 * search_docs 응답 재료: 준비 상태, 검색한 Pack, topK까지의 결과
 */
public record SearchPage(
        Readiness readiness,
        ResolvedPack resolved,
        List<ChunkResult> results
) {

    public SearchPage {

        results = List
                .copyOf(results);
    }
}
