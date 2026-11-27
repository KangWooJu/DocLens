package io.github.kangwooju.doclens.mcp.search.converter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.search.dto.SearchTerms;

/**
 * 질의 문자열의 FTS5 MATCH 식과 식별자 후보 변환
 * 단어마다 따옴표로 감싸 OR로 연결, 특수 문자는 글자 그대로 검색
 */
@Component
public class SearchTermsConverter {

    private static final Pattern WHITESPACE = Pattern
            .compile("\\s+");

    private static final Pattern SEARCHABLE = Pattern
            .compile("[\\p{L}\\p{N}]");

    /** 질의 문자열의 검색어 변환 */
    public SearchTerms toTerms(String query) {

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
