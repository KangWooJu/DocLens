package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 문서가 실제로 쓴 include 원본 하나, tags는 태그 지정이 없으면 null */
public record ResolvedInclude(
        String url,
        String rawSha256,
        String tags
) {
}
