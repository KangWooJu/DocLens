package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/**
 * 검색 결과 인용, Pack에 있는 출처 값만 포함
 * url은 공개 문서 주소가 있으면 그 값, 없으면 문서 수집 주소
 */
public record Citation(
        String url,
        String sourceUrl,
        String originalUrl,
        String finalUrl,
        List<String> headingPath,
        String anchor,
        String packId,
        String buildId,
        String exactVersion,
        String applicability,
        String checksum,
        List<String> blockIds,
        List<SourcePointer> sourcePointer
) {

    /** 목록 불변 사본 */
    public Citation {

        headingPath = List
                .copyOf(headingPath);
        blockIds = List
                .copyOf(blockIds);
        sourcePointer = List
                .copyOf(sourcePointer);
    }
}
