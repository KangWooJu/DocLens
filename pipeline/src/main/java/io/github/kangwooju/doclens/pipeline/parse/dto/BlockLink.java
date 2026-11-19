package io.github.kangwooju.doclens.pipeline.parse.dto;

/**
 * 블록 안 링크, start는 블록 텍스트 안 시작 위치(code point)
 * targetUrl은 finalUrl 기준으로 해석하고 fragment를 뗀 http(s) 주소, 해석할 수 없으면 null
 */
public record BlockLink(
        String label,
        String href,
        String targetUrl,
        String targetAnchor,
        int start
) {
}
