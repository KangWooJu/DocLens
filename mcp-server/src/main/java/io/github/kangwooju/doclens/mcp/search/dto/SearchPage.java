package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;

/** search_docs 응답 데이터 */
public record SearchPage(
        Readiness readiness,
        ResolvedPack resolved,
        List<ChunkResult> results
) {

    /** 결과 목록 불변 사본 */
    public SearchPage {

        results = List
                .copyOf(results);
    }
}
