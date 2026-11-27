package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 빈 글 xref에 대상 제목을 준 같은 Build의 쪽 원본 하나 */
public record TitleSource(
        String url,
        String rawSha256
) {
}
