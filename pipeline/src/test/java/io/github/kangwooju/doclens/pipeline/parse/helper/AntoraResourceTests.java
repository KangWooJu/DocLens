package io.github.kangwooju.doclens.pipeline.parse.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocRules;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;

@DisplayName("Antora resource id의 저장소 경로")
class AntoraResourceTests {

    private static final AsciidocRules RULES = ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE
            .asciidoc();

    private static final String MODULES = "documentation/spring-boot-docs/src/docs/antora/modules/";

    @Test
    @DisplayName("ROOT example, partial, module partial, 상대 경로 정리는 경로, 탈출과 모르는 family와 일반 경로는 null")
    void resolvesResourceIds() {

        List<List<String>> rows = List
                .of(
                        Arrays
                                .asList(
                                        "ROOT:example$remote/run.txt",
                                        "documentation/spring-boot-docs/src/main/remote/run.txt"
                                ),
                        Arrays
                                .asList(
                                        "partial$snippet.adoc",
                                        MODULES + "reference/partials/snippet.adoc"
                                ),
                        Arrays
                                .asList(
                                        "ROOT:partial$starters/list.adoc",
                                        MODULES + "ROOT/partials/starters/list.adoc"
                                ),
                        Arrays
                                .asList(
                                        "partial$a/../b/./c.adoc",
                                        MODULES + "reference/partials/b/c.adoc"
                                ),
                        Arrays
                                .asList(
                                        "partial$../../../../../../../../etc/hosts",
                                        null
                                ),
                        Arrays
                                .asList(
                                        "image$logo.png",
                                        null
                                ),
                        Arrays
                                .asList(
                                        "/etc/hosts",
                                        null
                                ),
                        Arrays
                                .asList(
                                        "https://example.com/x",
                                        null
                                )
                );

        for (List<String> row : rows) {
            assertEquals(
                    row
                            .get(1),
                    AsciidocHtmlHelper
                            .resourcePath(
                                    RULES,
                                    row
                                            .getFirst(),
                                    "reference"
                            ),
                    row
                            .getFirst()
            );
        }
    }
}
