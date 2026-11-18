package io.github.kangwooju.doclens.core.pack;

/** 탭 그룹 코드 블록의 변형 하나(블록마다 기본 변형 정확히 1개) */
public record CodeVariant(
        int blockIndex,
        VariantGroupType groupType,
        String variant,
        int ordinal,
        boolean isDefault,
        String code,
        String sourceMapJson
) {

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
