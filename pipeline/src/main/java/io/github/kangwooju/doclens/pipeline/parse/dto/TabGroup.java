package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;

/**
 * 탭 그룹과 모든 변형, markedOrdinals는 서버 HTML에 선택 표식이 붙은 변형 순번
 * defaultVariant와 defaultEvidence는 프로필 정책 판정 뒤 채움
 */
public record TabGroup(
        String groupLocator,
        String elementId,
        VariantGroupType groupType,
        List<Integer> markedOrdinals,
        String defaultVariant,
        String defaultEvidence,
        List<TabVariant> variants
) {

    /** 목록 불변 사본 */
    public TabGroup {
        markedOrdinals = List
                .copyOf(markedOrdinals);
        variants = List
                .copyOf(variants);
    }
}
