package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

/** FTS5 MATCH 식과 정확 식별자 후보, 글자나 숫자가 없는 질의면 matchExpression은 null */
public record SearchTerms(
        String matchExpression,
        List<String> identifiers
) {

    /** 식별자 목록 불변 사본 */
    public SearchTerms {

        identifiers = List
                .copyOf(identifiers);
    }
}
