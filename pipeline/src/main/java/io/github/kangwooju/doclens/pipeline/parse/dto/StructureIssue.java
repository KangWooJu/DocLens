package io.github.kangwooju.doclens.pipeline.parse.dto;

import io.github.kangwooju.doclens.pipeline.parse.type.StructureIssueType;

/** 순회가 건너뛴 요소의 종류와 본문 root 기준 DOM 경로 */
public record StructureIssue(
        StructureIssueType type,
        String domPath
) {
}
