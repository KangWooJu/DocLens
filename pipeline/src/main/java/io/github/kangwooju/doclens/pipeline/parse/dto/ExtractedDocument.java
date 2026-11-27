package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/**
 * 본문 순회 결과, 탭 기본 변형은 아직 정하지 않은 상태
 * title은 섹션이 없으면 null, issues는 순회가 표현하지 못하고 건너뛴 요소
 */
public record ExtractedDocument(
        String title,
        List<StructuredSection> sections,
        List<StructureIssue> issues
) {

    /** 목록 불변 사본 */
    public ExtractedDocument {
        sections = List
                .copyOf(sections);
        issues = List
                .copyOf(issues);
    }
}
