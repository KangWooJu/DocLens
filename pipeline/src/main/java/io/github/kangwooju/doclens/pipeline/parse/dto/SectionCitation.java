package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 섹션 인용 주소, readerUrl은 공개 문서 사이트, sourceUrl은 commit 고정 원본 줄 */
public record SectionCitation(
        String readerUrl,
        String sourceUrl
) {
}
