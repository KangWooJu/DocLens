package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/**
 * 검색 결과 인용, Pack에 출처가 있는 값만 담음
 *
 * <p>originalUrl·finalUrl·memberHash·sourcePointer는 Pack에 없어 내지 않음(C·D 단계 공백)
 */
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

    public Citation {

        headingPath = List
                .copyOf(headingPath);
    }
}
