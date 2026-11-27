package io.github.kangwooju.doclens.pipeline.parse.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("include 태그 필터")
class IncludeTagFilterTests {

    private static final String SOURCE = """
            head
            // tag::a[]
            alpha
            // tag::inner[]
            inner
            // end::inner[]
            // end::a[]
            middle
            // tag::b[]
            beta
            // end::b[]
            tail
            """;

    @Test
    @DisplayName("AsciiDoc 태그 규칙 표, 지시 줄은 항상 빠지고 없는 태그와 닫히지 않은 태그는 null")
    void filtersByTagRules() {

        List<List<String>> rows = List
                .of(
                        List
                                .of(
                                        "a",
                                        "alpha\ninner\n"
                                ),
                        List
                                .of(
                                        "a;b",
                                        "alpha\ninner\nbeta\n"
                                ),
                        List
                                .of(
                                        "a,b",
                                        "alpha\ninner\nbeta\n"
                                ),
                        List
                                .of(
                                        "*",
                                        "alpha\ninner\nbeta\n"
                                ),
                        List
                                .of(
                                        "**",
                                        "head\nalpha\ninner\nmiddle\nbeta\ntail\n"
                                ),
                        List
                                .of(
                                        "!a",
                                        "head\nmiddle\nbeta\ntail\n"
                                ),
                        List
                                .of(
                                        "inner",
                                        "inner\n"
                                ),
                        List
                                .of(
                                        "a;!inner",
                                        "alpha\n"
                                ),
                        List
                                .of(
                                        "**;!b",
                                        "head\nalpha\ninner\nmiddle\ntail\n"
                                )
                );

        for (List<String> row : rows) {
            assertEquals(
                    row
                            .get(1),
                    AsciidocHtmlHelper
                            .filterTags(
                                    SOURCE,
                                    row
                                            .getFirst()
                            ),
                    row
                            .getFirst()
            );
        }

        assertEquals(
                SOURCE,
                AsciidocHtmlHelper
                        .filterTags(
                                SOURCE,
                                null
                        )
        );
        assertEquals(
                null,
                AsciidocHtmlHelper
                        .filterTags(
                                SOURCE,
                                "absent"
                        )
        );
        assertEquals(
                null,
                AsciidocHtmlHelper
                        .filterTags(
                                "// tag::open[]\nbody\n",
                                "open"
                        )
        );
    }
}
