package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 블록 텍스트 안 인라인 코드와 시작 위치(code point) */
public record InlineCode(
        String text,
        int start
) {
}
