package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 기본 변형 판정 결과, evidence는 SERVER_MARKER 또는 PROFILE_RULE:규칙 이름 */
public record DefaultVariantChoice(
        int ordinal,
        String evidence
) {
}
