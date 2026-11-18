package io.github.kangwooju.doclens.mcp.search.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.PackFiles;
import io.github.kangwooju.doclens.core.pack.TestPacks;
import io.github.kangwooju.doclens.mcp.global.response.InvalidToolInputException;
import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.ToolErrorCode;
import io.github.kangwooju.doclens.mcp.global.response.ToolFailureException;
import io.github.kangwooju.doclens.mcp.pack.PackInstalls;
import io.github.kangwooju.doclens.mcp.search.SearchFixtures;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkResult;
import io.github.kangwooju.doclens.mcp.search.dto.Citation;
import io.github.kangwooju.doclens.mcp.search.dto.SearchPage;
import io.github.kangwooju.doclens.mcp.search.dto.SearchRequest;

@DisplayName("SearchFacade")
class SearchFacadeTests {

    private static final String OLD_BUILD = "11111111-1111-4111-8111-111111111111";

    private static final String NEW_BUILD = "22222222-2222-4222-8222-222222222222";

    @TempDir
    Path cacheRoot;

    @Test
    @DisplayName("설치 Pack 없음의 NOT_READY, 맞는 Pack 없음의 NOT_FOUND")
    void rejectsMissingPacks() {

        try (SearchFixtures empty = SearchFixtures
                .over(cacheRoot)) {
            assertFailure(
                    ToolErrorCode.NOT_READY,
                    empty,
                    request(
                            TestPacks.VERSION,
                            null,
                            5
                    )
            );
        }

        install(
                OLD_BUILD,
                "old"
        );

        try (SearchFixtures search = SearchFixtures
                .over(cacheRoot)) {
            assertFailure(
                    ToolErrorCode.NOT_FOUND,
                    search,
                    request(
                            "3.5.0",
                            null,
                            5
                    )
            );
            assertFailure(
                    ToolErrorCode.NOT_FOUND,
                    search,
                    request(
                            TestPacks.VERSION,
                            NEW_BUILD,
                            5
                    )
            );
        }
    }

    @Test
    @DisplayName("buildId 없을 때 가장 늦게 설치한 build, 지정 시 그 build 검색")
    void resolvesBuild() throws IOException {

        setModified(
                install(
                        OLD_BUILD,
                        "old"
                ),
                "2026-11-01T00:00:00Z"
        );
        setModified(
                install(
                        NEW_BUILD,
                        "new"
                ),
                "2026-11-02T00:00:00Z"
        );

        try (SearchFixtures search = SearchFixtures
                .over(cacheRoot)) {
            assertEquals(
                    NEW_BUILD,
                    search
                            .searchFacade()
                            .search(request(
                                    TestPacks.VERSION,
                                    null,
                                    5
                            ))
                            .resolved()
                            .buildId()
            );
            assertEquals(
                    OLD_BUILD,
                    search
                            .searchFacade()
                            .search(request(
                                    TestPacks.VERSION,
                                    OLD_BUILD,
                                    5
                            ))
                            .resolved()
                            .buildId()
            );
        }
    }

    @Test
    @DisplayName("topK 개수·1부터 순위·Pack 값만으로 만든 인용")
    void buildsRankedResultsWithCitations() {

        Path file = install(
                OLD_BUILD,
                "only"
        );

        try (SearchFixtures search = SearchFixtures
                .over(cacheRoot)) {
            SearchPage page = search
                    .searchFacade()
                    .search(request(
                            TestPacks.VERSION,
                            null,
                            2
                    ));
            ChunkResult first = page
                    .results()
                    .getFirst();
            Citation citation = first
                    .citation();

            assertEquals(
                    Readiness.READY_STALE,
                    page
                            .readiness()
            );
            assertEquals(
                    2,
                    page
                            .results()
                            .size()
            );
            assertEquals(
                    List
                            .of(
                                    1,
                                    2
                            ),
                    page
                            .results()
                            .stream()
                            .map(result -> result
                                    .attributes()
                                    .rank())
                            .toList()
            );
            assertEquals(
                    "CHUNK",
                    first
                            .kind()
            );
            assertEquals(
                    "https://docs.spring.io/spring-boot/only.html",
                    citation
                            .url()
            );
            assertEquals(
                    List
                            .of("only"),
                    citation
                            .headingPath()
            );
            assertEquals(
                    "EXACT_RELEASE",
                    citation
                            .applicability()
            );
            assertEquals(
                    PackFiles
                            .sha256(file),
                    citation
                            .checksum()
            );
            assertEquals(
                    page
                            .resolved()
                            .packId(),
                    citation
                            .packId()
            );
        }
    }

    @Test
    @DisplayName("알 수 없는 documentTypes 값의 INVALID_INPUT")
    void rejectsUnknownDocumentType() {

        assertThrows(
                InvalidToolInputException.class,
                () -> new SearchRequest(
                        "spring",
                        TestPacks.PROJECT,
                        TestPacks.VERSION,
                        null,
                        5,
                        List
                                .of("tutorial")
                )
        );
    }

    // buildId·문서 키 지정 Pack 설치(청크 셋 모두 spring 포함)
    private Path install(
            String buildId,
            String key
    ) {

        return PackInstalls
                .install(
                        cacheRoot,
                        TestPacks
                                .builder()
                                .buildId(buildId)
                                .document(key)
                                .chunk("spring boot one")
                                .chunk("spring boot two")
                                .chunk("spring boot three")
                );
    }

    // 기본 프로젝트의 spring 검색 요청
    private static SearchRequest request(
            String exactVersion,
            String buildId,
            int topK
    ) {

        return new SearchRequest(
                "spring",
                TestPacks.PROJECT,
                exactVersion,
                buildId,
                topK,
                List
                        .of()
        );
    }

    // 파일 수정 시각 지정
    private static void setModified(
            Path file,
            String instant
    ) throws IOException {

        Files
                .setLastModifiedTime(
                        file,
                        FileTime
                                .from(Instant
                                        .parse(instant))
                );
    }

    // 검색 요청의 지정 오류 코드 실패 확인
    private static void assertFailure(
            ToolErrorCode expected,
            SearchFixtures search,
            SearchRequest request
    ) {

        ToolFailureException failure = assertThrows(
                ToolFailureException.class,
                () -> search
                        .searchFacade()
                        .search(request)
        );

        assertEquals(
                expected,
                failure
                        .code()
        );
    }
}
