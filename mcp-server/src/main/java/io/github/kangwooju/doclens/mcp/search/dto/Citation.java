package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/** 검색 결과 인용, Pack에 있는 출처 값만 포함 */
public record Citation(
        String url,
        List<String> headingPath,
        String anchor,
        String packId,
        String buildId,
        String exactVersion,
        String applicability,
        String checksum
) {

    /** headingPath 불변 사본 */
    public Citation {

        headingPath = List
                .copyOf(headingPath);
    }
}
