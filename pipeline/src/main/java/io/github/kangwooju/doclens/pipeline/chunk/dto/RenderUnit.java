package io.github.kangwooju.doclens.pipeline.chunk.dto;

import java.util.List;

import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;

/**
 * 렌더링 텍스트를 나눌 수 있는 단위, 목록은 항목 줄, 표는 rowspan으로 묶인 본문 행
 * charStart는 렌더링 텍스트 안 시작 위치(code point), 표 행 범위는 본문 행 기준
 */
public record RenderUnit(
        String text,
        int charStart,
        int rowStart,
        int rowEndExclusive,
        List<InlineCode> inlineCodes,
        List<BlockLink> links
) {

    /** 목록 불변 사본 */
    public RenderUnit {
        inlineCodes = List
                .copyOf(inlineCodes);
        links = List
                .copyOf(links);
    }
}
