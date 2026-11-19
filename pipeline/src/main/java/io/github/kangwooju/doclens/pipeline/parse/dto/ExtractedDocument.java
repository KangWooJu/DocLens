package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** 본문 순회 결과, 탭 기본 변형은 아직 정하지 않은 상태 */
public record ExtractedDocument(
        String title,
        List<StructuredSection> sections
) {

    /** 섹션 목록 불변 사본 */
    public ExtractedDocument {
        sections = List
                .copyOf(sections);
    }
}
