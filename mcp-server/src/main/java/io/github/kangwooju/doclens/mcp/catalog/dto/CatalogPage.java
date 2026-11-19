package io.github.kangwooju.doclens.mcp.catalog.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;

/** 목록 Tool 응답 데이터, limit까지 자른 결과와 truncated 여부 */
public record CatalogPage(
        Readiness readiness,
        List<?> results,
        boolean truncated
) {

    /** 결과 목록 불변 사본 */
    public CatalogPage {

        results = List
                .copyOf(results);
    }

    /** 전체 결과를 limit개로 자른 페이지 */
    public static CatalogPage of(
            Readiness readiness,
            List<?> all,
            int limit
    ) {

        return new CatalogPage(
                readiness,
                all
                        .stream()
                        .limit(limit)
                        .toList(),
                all
                        .size() > limit
        );
    }
}
