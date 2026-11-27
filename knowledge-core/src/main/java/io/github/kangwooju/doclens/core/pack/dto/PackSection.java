package io.github.kangwooju.doclens.core.pack.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.support.Require;

/** 섹션과 청크 목록(ordinal 순), sectionId는 중복 여부만 검사 */
public record PackSection(
        String sectionId,
        String parentSectionId,
        String anchor,
        List<String> headingPath,
        int ordinal,
        List<PackChunk> chunks
) {

    /** 필수 값 검사, 목록 불변 사본 */
    public PackSection {

        Require
                .text(
                        sectionId,
                        "sectionId"
                );
        Require
                .nonNegative(
                        ordinal,
                        "section ordinal"
                );
        headingPath = List
                .copyOf(headingPath);
        chunks = List
                .copyOf(chunks);
    }
}
