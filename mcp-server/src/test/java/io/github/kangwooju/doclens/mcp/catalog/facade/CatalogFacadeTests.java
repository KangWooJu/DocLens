package io.github.kangwooju.doclens.mcp.catalog.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.fixture.TestPacks;
import io.github.kangwooju.doclens.mcp.catalog.dto.CatalogPage;
import io.github.kangwooju.doclens.mcp.catalog.dto.ProjectResult;
import io.github.kangwooju.doclens.mcp.catalog.dto.VersionResult;
import io.github.kangwooju.doclens.mcp.global.cache.CacheDirectory;
import io.github.kangwooju.doclens.mcp.global.response.exception.ToolFailureException;
import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;
import io.github.kangwooju.doclens.mcp.pack.PackInstalls;
import io.github.kangwooju.doclens.mcp.pack.policy.VersionOrderPolicy;
import io.github.kangwooju.doclens.mcp.pack.service.InstalledPackService;
import io.github.kangwooju.doclens.mcp.pack.store.InstalledPackStore;

@DisplayName("프로젝트, 버전 목록 Facade")
class CatalogFacadeTests {

    @TempDir
    Path cacheRoot;

    private InstalledPackStore store;

    private CatalogFacade facade;

    // 프로젝트 둘, 버전 셋 설치
    @BeforeEach
    void installPacks() {

        install(
                "spring-boot",
                "4.0.0",
                "33333333-3333-4333-8333-333333333333"
        );
        install(
                "spring-boot",
                "3.5.0",
                "11111111-1111-4111-8111-111111111111"
        );
        install(
                "spring-boot",
                "4.0.0",
                "22222222-2222-4222-8222-222222222222"
        );
        install(
                "spring-framework",
                "7.0.0",
                "44444444-4444-4444-8444-444444444444"
        );
        store = new InstalledPackStore(new CacheDirectory(cacheRoot));
        facade = new CatalogFacade(new InstalledPackService(
                store,
                new VersionOrderPolicy()
        ));
    }

    // Store Reader 닫기
    @AfterEach
    void closeStore() {

        store
                .close();
    }

    @Test
    @DisplayName("프로젝트별 버전, build 개수와 limit 초과 표시")
    void listsProjects() {

        CatalogPage page = facade
                .projects(20);
        ProjectResult boot = (ProjectResult) page
                .results()
                .getFirst();

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
        assertFalse(page
                .truncated());
        assertEquals(
                "spring-boot",
                boot
                        .id()
        );
        assertEquals(
                2,
                boot
                        .attributes()
                        .installedVersionCount()
        );
        assertEquals(
                3,
                boot
                        .attributes()
                        .installedBuildCount()
        );

        CatalogPage limited = facade
                .projects(1);

        assertEquals(
                1,
                limited
                        .results()
                        .size()
        );
        assertTrue(limited
                .truncated());
    }

    @Test
    @DisplayName("버전 순서와 buildId 순서의 버전 목록")
    void listsVersionsInOrder() {

        List<String> ids = facade
                .versions(
                        "spring-boot",
                        20
                )
                .results()
                .stream()
                .map(result -> ((VersionResult) result)
                        .id())
                .toList();

        assertEquals(
                List
                        .of(
                                "spring-boot-3.5.0-test/11111111-1111-4111-8111-111111111111",
                                "spring-boot-4.0.0-test/22222222-2222-4222-8222-222222222222",
                                "spring-boot-4.0.0-test/33333333-3333-4333-8333-333333333333"
                        ),
                ids
        );
    }

    @Test
    @DisplayName("설치되지 않은 프로젝트의 NOT_FOUND")
    void rejectsUnknownProject() {

        ToolFailureException failure = assertThrows(
                ToolFailureException.class,
                () -> facade
                        .versions(
                                "Spring-Boot",
                                20
                        )
        );

        assertEquals(
                ToolErrorCode.NOT_FOUND,
                failure
                        .code()
        );
    }

    // 프로젝트, 버전, buildId를 지정한 Pack 설치
    private void install(
            String project,
            String version,
            String buildId
    ) {

        PackInstalls
                .install(
                        cacheRoot,
                        TestPacks
                                .builder()
                                .project(project)
                                .version(version)
                                .buildId(buildId)
                                .document("overview")
                                .chunk(project + " " + version + " overview")
                );
    }
}
