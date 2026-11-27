package io.github.kangwooju.doclens.core.pack.dto;

import io.github.kangwooju.doclens.core.pack.support.Require;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;

/** 탭으로 묶인 코드 블록의 변형 하나, 블록마다 기본 변형은 1개 */
public record CodeVariant(
        int blockIndex,
        VariantGroupType groupType,
        String variant,
        int ordinal,
        boolean isDefault,
        String code,
        String sourceMapJson
) {

    /** 필수 값과 음수 검사 */
    public CodeVariant {

        Require
                .nonNegative(
                        blockIndex,
                        "blockIndex"
                );
        Require
                .present(
                        groupType,
                        "groupType"
                );
        Require
                .text(
                        variant,
                        "variant"
                );
        Require
                .nonNegative(
                        ordinal,
                        "variant ordinal"
                );
        Require
                .present(
                        code,
                        "code"
                );
        Require
                .text(
                        sourceMapJson,
                        "sourceMapJson"
                );
    }
}
