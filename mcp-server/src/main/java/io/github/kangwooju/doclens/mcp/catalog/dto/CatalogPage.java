package io.github.kangwooju.doclens.mcp.catalog.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.Readiness;

/**
 * 목록 Tool 응답 재료: 준비 상태, limit까지의 결과, limit 초과 여부
 */
public record CatalogPage(
        Readiness readiness,
        List<?> results,
        boolean truncated
) {

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
