package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질의에서 만든 FTS5 MATCH 식과 정확 식별자 후보(F12)
 *
 * <p>공백 단위 단어마다 따옴표 구절로 감싸 OR 연결, 연산자·괄호·따옴표는 글자 그대로 검색
 * <p>글자·숫자가 없는 단어는 MATCH 식에서 제외, 남는 단어가 없으면 matchExpression은 null
 * <p>식별자 후보는 질의 전체와 각 단어(대소문자 구분)
 */
public record SearchTerms(
        String matchExpression,
        List<String> identifiers
) {

    private static final Pattern WHITESPACE = Pattern
            .compile("\\s+");

    private static final Pattern SEARCHABLE = Pattern
            .compile("[\\p{L}\\p{N}]");

    public SearchTerms {

        identifiers = List
                .copyOf(identifiers);
    }

    /** 질의 문자열 기준 검색어 */
    public static SearchTerms from(String query) {

        String whole = query
                .strip();
        List<String> words = whole
                .isEmpty()
                ? List
                        .of()
                : List
                        .of(WHITESPACE
                                .split(whole));
        Set<String> identifiers = new LinkedHashSet<>();
        List<String> phrases = words
                .stream()
                .filter(word -> SEARCHABLE
                        .matcher(word)
                        .find())
                .map(word -> "\"" + word
                        .replace(
                                "\"",
                                "\"\""
                        ) + "\"")
                .distinct()
                .toList();

        if (!whole
                .isEmpty()) {
            identifiers
                    .add(whole);
        }

        identifiers
                .addAll(words);

        return new SearchTerms(
                phrases
                        .isEmpty()
                        ? null
                        : String
                                .join(
                                        " OR ",
                                        phrases
                                ),
                List
                        .copyOf(identifiers)
        );
    }
}
