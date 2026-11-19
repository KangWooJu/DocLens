package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** heading 하나가 여는 섹션과 직속 블록, ordinal은 문서 안 섹션 순번 */
public record StructuredSection(
        String sectionId,
        String parentSectionId,
        String headingLocator,
        String anchor,
        List<String> headingPath,
        int level,
        int ordinal,
        List<Block> blocks
) {

    /** 목록 불변 사본 */
    public StructuredSection {
        headingPath = List
                .copyOf(headingPath);
        blocks = List
                .copyOf(blocks);
    }
}
