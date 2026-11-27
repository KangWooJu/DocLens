package io.github.kangwooju.doclens.mcp.pack.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.fixture.TestPacks;
import io.github.kangwooju.doclens.core.pack.support.PackFiles;
import io.github.kangwooju.doclens.mcp.global.cache.CacheDirectory;
import io.github.kangwooju.doclens.mcp.pack.PackInstalls;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;

@DisplayName("설치 Pack 탐색")
class InstalledPackStoreTests {

    private static final String OTHER_BUILD = "22222222-2222-4222-8222-222222222222";

    @TempDir
    Path cacheRoot;

    @TempDir
    Path elsewhere;

    @Test
    @DisplayName("설치 경로 규칙을 지킨 Pack 발견과 메타데이터")
    void findsInstalledPacks() {

        Path file = PackInstalls
                .install(
                        cacheRoot,
                        pack()
                );
        PackInstalls
                .install(
                        cacheRoot,
                        pack()
                                .version("3.5.0")
                                .buildId(OTHER_BUILD)
                );

        try (InstalledPackStore store = store()) {
            List<InstalledPack> installed = store
                    .installed();

            assertEquals(
                    2,
                    installed
                            .size()
            );

            InstalledPack first = installed
                    .stream()
                    .filter(pack -> pack
                            .exactVersion()
                            .equals(TestPacks.VERSION))
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    "spring-boot-4.0.0-test",
                    first
                            .packId()
            );
            assertEquals(
                    TestPacks.PROJECT,
                    first
                            .project()
            );
            assertEquals(
                    PackFiles
                            .sha256(file),
                    first
                            .checksum()
            );
            assertEquals(
                    List
                            .of(
                                    "BM25",
                                    "SECTIONS",
                                    "VARIANTS"
                            ),
                    first
                            .capabilities()
            );
        }
    }

    @Test
    @DisplayName("packs 디렉터리 없음의 빈 목록")
    void emptyWithoutPacksDirectory() {

        try (InstalledPackStore store = store()) {
            assertTrue(store
                    .installed()
                    .isEmpty());
        }
    }

    @Test
    @DisplayName("이름과 다른 SHA-256 파일 거절")
    void skipsChecksumMismatch() throws IOException {

        Path file = PackInstalls
                .install(
                        cacheRoot,
                        pack()
                );
        Files
                .move(
                        file,
                        file
                                .resolveSibling("0"
                                        .repeat(64) + ".pack")
                );

        assertNothingInstalled();
    }

    @Test
    @DisplayName("심볼릭 링크 파일과 디렉터리 거절")
    void skipsSymbolicLinks() throws IOException {

        Path file = PackInstalls
                .install(
                        elsewhere,
                        pack()
                );
        Path relative = elsewhere
                .relativize(file);
        Path linkedFile = cacheRoot
                .resolve(relative);
        Files
                .createDirectories(linkedFile
                        .getParent());
        Files
                .createSymbolicLink(
                        linkedFile,
                        file
                );

        assertNothingInstalled();

        Files
                .delete(linkedFile);
        Files
                .delete(linkedFile
                        .getParent());
        Files
                .createSymbolicLink(
                        linkedFile
                                .getParent(),
                        file
                                .getParent()
                );

        assertNothingInstalled();
    }

    @Test
    @DisplayName("디렉터리와 다른 packId 거절")
    void skipsLocationMismatch() throws IOException {

        Path file = PackInstalls
                .install(
                        cacheRoot,
                        pack()
                );
        Path moved = cacheRoot
                .resolve("packs/other-pack")
                .resolve(file
                        .getParent()
                        .getFileName())
                .resolve(file
                        .getFileName());
        Files
                .createDirectories(moved
                        .getParent());
        Files
                .move(
                        file,
                        moved
                );

        assertNothingInstalled();
    }

    @Test
    @DisplayName("Pack 검증 실패 파일 거절")
    void skipsInvalidPack() throws IOException {

        Path junk = elsewhere
                .resolve("junk");
        Files
                .writeString(
                        junk,
                        "not a pack",
                        StandardCharsets.UTF_8
                );
        Path target = cacheRoot
                .resolve("packs/spring-boot-4.0.0-test/11111111-1111-4111-8111-111111111111")
                .resolve(PackFiles
                        .sha256(junk)
                        .substring("sha256:"
                                .length()) + ".pack");
        Files
                .createDirectories(target
                        .getParent());
        Files
                .move(
                        junk,
                        target
                );

        assertNothingInstalled();
    }

    @Test
    @DisplayName("같은 buildId 두 번째 파일 거절")
    void skipsDuplicateBuild() {

        PackInstalls
                .install(
                        cacheRoot,
                        pack()
                );
        PackInstalls
                .install(
                        cacheRoot,
                        pack()
                                .chunk("Another body")
                );

        try (InstalledPackStore store = store()) {
            assertEquals(
                    1,
                    store
                            .installed()
                            .size()
            );
        }
    }

    // 문서 하나, 청크 하나인 기본 Pack builder
    private static TestPacks.Builder pack() {

        return TestPacks
                .builder()
                .document("overview")
                .chunk("Spring Boot overview");
    }

    // 시험 캐시 루트 기준 Store
    private InstalledPackStore store() {

        return new InstalledPackStore(new CacheDirectory(cacheRoot));
    }

    // 설치 Pack 없음 확인
    private void assertNothingInstalled() {

        try (InstalledPackStore store = store()) {
            assertTrue(store
                    .installed()
                    .isEmpty());
        }
    }
}
