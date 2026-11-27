package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;
import java.util.Map;

/**
 * AsciiDoc 원본 하나를 바꾼 HTML과 판정 재료
 * titleSources는 빈 글 xref에 제목을 준 같은 Build의 다른 쪽, unresolved는 보류 사유 후보, anchorLines는 원본의 섹션 id 줄 번호, titleLine은 문서 제목 줄 번호이고 없으면 0
 */
public record AsciidocHtml(
        String html,
        List<ResolvedInclude> includes,
        List<TitleSource> titleSources,
        List<String> unresolved,
        Map<String, Integer> anchorLines,
        int titleLine,
        Map<String, Integer> counts
) {

    /** 목록과 대응표 불변 사본 */
    public AsciidocHtml {
        includes = List
                .copyOf(includes);
        titleSources = List
                .copyOf(titleSources);
        unresolved = List
                .copyOf(unresolved);
        anchorLines = Map
                .copyOf(anchorLines);
        counts = Map
                .copyOf(counts);
    }
}
