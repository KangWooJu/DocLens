package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** 표 셀, 병합 정보는 원래 rowspan과 colspan 그대로 */
public record TableCell(
        String text,
        int rowspan,
        int colspan,
        List<InlineCode> inlineCodes,
        List<BlockLink> links,
        String domPath
) {

    /** 목록 불변 사본 */
    public TableCell {
        inlineCodes = List
                .copyOf(inlineCodes);
        links = List
                .copyOf(links);
    }
}
