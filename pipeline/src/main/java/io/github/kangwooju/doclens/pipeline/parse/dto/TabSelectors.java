package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 탭 그룹과 탭 라벨 선택자, 패널은 aria-labelledby로 연결 */
public record TabSelectors(
        String group,
        String tab
) {
}
