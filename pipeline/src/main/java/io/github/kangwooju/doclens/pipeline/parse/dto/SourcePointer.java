package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 블록의 원본 위치, domPath는 본문 root부터 요소 자식 순번을 / 로 이은 경로 */
public record SourcePointer(
        String domPath,
        String elementId,
        String headingLocator,
        int blockOrdinal
) {
}
