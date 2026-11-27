package io.github.kangwooju.doclens.mcp.search.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.mcp.search.dto.SearchTerms;

@DisplayName("검색어 변환")
class SearchTermsConverterTests {

    @Test
    @DisplayName("단어별 따옴표 구절 OR 연결과 따옴표 이중화")
    void quotesEveryWord() {

        SearchTerms terms = new SearchTermsConverter()
                .toTerms("NEAR(a \"b c* -d");

        assertEquals(
                "\"NEAR(a\" OR \"\"\"b\" OR \"c*\" OR \"-d\"",
                terms
                        .matchExpression()
        );
        assertEquals(
                List
                        .of(
                                "NEAR(a \"b c* -d",
                                "NEAR(a",
                                "\"b",
                                "c*",
                                "-d"
                        ),
                terms
                        .identifiers()
        );
    }

    @Test
    @DisplayName("글자나 숫자 없는 단어의 MATCH 제외와 식별자 후보 유지")
    void dropsSymbolOnlyWordsFromMatch() {

        SearchTerms terms = new SearchTermsConverter()
                .toTerms(" @@ ( ");

        assertNull(terms
                .matchExpression());
        assertEquals(
                List
                        .of(
                                "@@ (",
                                "@@",
                                "("
                        ),
                terms
                        .identifiers()
        );
        assertEquals(
                List
                        .of(),
                new SearchTermsConverter()
                        .toTerms("   ")
                        .identifiers()
        );
    }
}
