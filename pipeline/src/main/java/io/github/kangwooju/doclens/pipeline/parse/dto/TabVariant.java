package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** 탭 변형 하나, 패널 안 블록은 blockId 없이 순서로만 구분 */
public record TabVariant(
        String label,
        String canonicalLabel,
        String variantKey,
        int ordinal,
        List<Block> blocks,
        SourcePointer sourcePointer
) {

    /** 블록 목록 불변 사본 */
    public TabVariant {
        blocks = List
                .copyOf(blocks);
    }
}
