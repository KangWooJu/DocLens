package io.github.kangwooju.doclens.core.pack;

import java.util.List;

/**
 * 섹션 하나와 그 청크들(청크 ordinal 오름차순).
 *
 * <p>sectionId 생성 공식은 parser가 정함(F19). Writer는 유일성만 검사.
 */
public record PackSection(
        String sectionId,
        String parentSectionId,
        String anchor,
        List<String> headingPath,
        int ordinal,
        List<PackChunk> chunks
) {

    public PackSection {

        Require.text(sectionId, "sectionId");
        Require.nonNegative(ordinal, "section ordinal");
        headingPath = List.copyOf(headingPath);
        chunks = List.copyOf(chunks);
    }
}
