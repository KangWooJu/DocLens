package io.github.kangwooju.doclens.mcp.search.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SearchTerms")
class SearchTermsTests {

    @Test
    @DisplayName("단어별 따옴표 구절 OR 연결과 따옴표 이중화")
    void quotesEveryWord() {

        SearchTerms terms = SearchTerms
                .from("NEAR(a \"b c* -d");

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
    @DisplayName("글자·숫자 없는 단어의 MATCH 제외와 식별자 후보 유지")
    void dropsSymbolOnlyWordsFromMatch() {

        SearchTerms terms = SearchTerms
                .from(" @@ ( ");

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
                SearchTerms
                        .from("   ")
                        .identifiers()
        );
    }
}
