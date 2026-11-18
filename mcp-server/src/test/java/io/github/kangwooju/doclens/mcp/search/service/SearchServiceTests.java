package io.github.kangwooju.doclens.mcp.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.DocumentType;
import io.github.kangwooju.doclens.core.pack.TestPacks;
import io.github.kangwooju.doclens.mcp.pack.PackInstalls;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.search.SearchFixtures;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkHit;
import io.github.kangwooju.doclens.mcp.search.dto.SearchTerms;

@DisplayName("SearchService")
class SearchServiceTests {

    private static final String IDENTIFIER = "C051Ident";

    @TempDir
    Path cacheRoot;

    @Test
    @DisplayName("c051: bm25 꼴찌 정확 식별자 청크의 상한 전 합류와 1등, 한 번만 등장")
    void exactIdentifierJoinsBeforeCap() {

        TestPacks.Builder builder = TestPacks
                .builder()
                .document("many");

        for (int index = 0; index < 51; index++) {
            builder
                    .chunk("spring spring short text " + index);
        }

        builder
                .chunk(
                        "spring " + "filler "
                                .repeat(60),
                        List
                                .of(IDENTIFIER)
                );
        String c051 = TestPacks
                .id("chunk:many:51");

        try (SearchFixtures search = install(builder)) {
            List<ChunkHit> lexicalOnly = candidates(
                    search,
                    "spring",
                    List
                            .of()
            );

            assertEquals(
                    SearchService.CANDIDATE_CAP,
                    lexicalOnly
                            .size()
            );
            assertTrue(lexicalOnly
                    .stream()
                    .noneMatch(hit -> hit
                            .chunkId()
                            .equals(c051)));

            List<ChunkHit> withIdentifier = candidates(
                    search,
                    "spring " + IDENTIFIER,
                    List
                            .of()
            );
            ChunkHit first = withIdentifier
                    .getFirst();

            assertEquals(
                    c051,
                    first
                            .chunkId()
            );
            assertTrue(first
                    .exactIdentifierMatch());
            assertNotNull(first
                    .lexicalScore());
            assertEquals(
                    SearchService.CANDIDATE_CAP,
                    withIdentifier
                            .stream()
                            .map(ChunkHit::chunkId)
                            .distinct()
                            .count()
            );

            List<ChunkHit> exactOnly = candidates(
                    search,
                    IDENTIFIER,
                    List
                            .of()
            );

            assertEquals(
                    1,
                    exactOnly
                            .size()
            );
            assertNull(exactOnly
                    .getFirst()
                    .lexicalScore());
            assertTrue(candidates(
                    search,
                    "c051ident",
                    List
                            .of()
            )
                    .isEmpty());
        }
    }

    @Test
    @DisplayName("FTS5 연산자·따옴표·괄호 질의의 오류 없는 글자 그대로 검색")
    void searchesOperatorsLiterally() {

        try (SearchFixtures search = install(TestPacks
                .builder()
                .document("syntax")
                .chunk("Use NOT and NEAR with quotes and parens"))) {
            assertFalse(candidates(
                    search,
                    "NOT",
                    List
                            .of()
            )
                    .isEmpty());
            assertFalse(candidates(
                    search,
                    "NEAR(with",
                    List
                            .of()
            )
                    .isEmpty());

            for (String query : List
                    .of(
                            "\"unbalanced",
                            "(parens",
                            "a* OR",
                            "-x",
                            "col:val",
                            "^start",
                            "AND",
                            "\"\"",
                            ")"
                    )) {
                candidates(
                        search,
                        query,
                        List
                                .of()
                );
            }
        }
    }

    @Test
    @DisplayName("documentTypes 조건의 BM25·정확 식별자 두 갈래 동일 적용과 동점 chunkId 순서")
    void filtersBothBranchesAndBreaksTies() {

        try (SearchFixtures search = install(TestPacks
                .builder()
                .document(
                        "reference-doc",
                        DocumentType.REFERENCE
                )
                .chunk("alpha text")
                .document(
                        "guide-doc",
                        DocumentType.GUIDE
                )
                .chunk(
                        "alpha text",
                        List
                                .of("Alpha")
                ))) {
            List<ChunkHit> guideOnly = candidates(
                    search,
                    "alpha",
                    List
                            .of("guide")
            );

            assertEquals(
                    List
                            .of(TestPacks
                                    .id("chunk:guide-doc:0")),
                    guideOnly
                            .stream()
                            .map(ChunkHit::chunkId)
                            .toList()
            );

            List<ChunkHit> referenceOnly = candidates(
                    search,
                    "Alpha",
                    List
                            .of("reference")
            );

            assertEquals(
                    List
                            .of(TestPacks
                                    .id("chunk:reference-doc:0")),
                    referenceOnly
                            .stream()
                            .map(ChunkHit::chunkId)
                            .toList()
            );
            assertFalse(referenceOnly
                    .getFirst()
                    .exactIdentifierMatch());

            List<String> tied = candidates(
                    search,
                    "text",
                    List
                            .of()
            )
                    .stream()
                    .map(ChunkHit::chunkId)
                    .toList();

            assertEquals(
                    tied
                            .stream()
                            .sorted()
                            .toList(),
                    tied
            );
        }
    }

    // builder Pack 설치와 검색 계층 조립
    private SearchFixtures install(TestPacks.Builder builder) {

        PackInstalls
                .install(
                        cacheRoot,
                        builder
                );

        return SearchFixtures
                .over(cacheRoot);
    }

    // 설치 Pack 하나의 후보 검색
    private static List<ChunkHit> candidates(
            SearchFixtures search,
            String query,
            List<String> documentTypes
    ) {

        InstalledPack pack = search
                .store()
                .installed()
                .getFirst();

        return search
                .searchService()
                .candidates(
                        pack,
                        SearchTerms
                                .from(query),
                        documentTypes
                );
    }
}
