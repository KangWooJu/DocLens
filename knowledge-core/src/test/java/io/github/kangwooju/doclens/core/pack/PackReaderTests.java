package io.github.kangwooju.doclens.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.PackValidationException.Reason;

@DisplayName("PackReader")
class PackReaderTests {

    @TempDir
    Path directory;

    @Test
    @DisplayName("정상 Pack의 변경 없는 열기와 메타데이터 조회")
    void opensValidPackWithoutChangingIt() {

        PackWriteResult written = writeSample();

        try (PackReader reader = PackReader
                .open(written
                        .path())) {
            PackInfo info = reader
                    .info();

            assertEquals(
                    "spring-boot",
                    info
                            .project()
            );
            assertEquals(
                    TestPacks.VERSION,
                    info
                            .docVersion()
            );
            assertEquals(
                    1,
                    info
                            .packSchemaVersion()
            );
            assertEquals(
                    1,
                    info
                            .minReaderVersion()
            );
            assertEquals(
                    written
                            .logicalContentSha256(),
                    info
                            .logicalContentSha256()
            );
            assertEquals(
                    List
                            .of(
                                    "BM25",
                                    "SECTIONS",
                                    "VARIANTS"
                            ),
                    info
                            .capabilities()
            );
            assertEquals(
                    2,
                    info
                            .documentCount()
            );
            assertEquals(
                    3,
                    info
                            .chunkCount()
            );
        }

        assertEquals(
                written
                        .checksum(),
                PackFiles
                        .sha256(written
                                .path())
        );

        for (String suffix : List
                .of(
                        "-journal",
                        "-wal",
                        "-shm"
                )) {
            assertFalse(Files
                    .exists(directory
                            .resolve("test.pack" + suffix)));
        }
    }

    @Test
    @DisplayName("심볼릭 링크 거부")
    void refusesSymbolicLink() throws Exception {

        PackWriteResult written = writeSample();
        Path link = Files
                .createSymbolicLink(
                        directory
                                .resolve("link.pack"),
                        written
                                .path()
                );

        assertReason(
                Reason.NOT_A_FILE,
                link
        );
    }

    @Test
    @DisplayName("Pack이 아닌 SQLite 데이터베이스 거부")
    void refusesDatabaseThatIsNotAPack() throws Exception {

        Path other = directory
                .resolve("other.db");

        try (Connection connection = DriverManager
                .getConnection("jdbc:sqlite:" + other);
             Statement statement = connection
                     .createStatement()) {
            statement
                    .execute("CREATE TABLE notes(text TEXT)");
        }

        assertReason(
                Reason.NOT_A_PACK,
                other
        );
    }

    @Test
    @DisplayName("더 높은 스키마 버전과 Reader 버전 요구 거부")
    void refusesNewerSchemaAndNewerReaderRequirement() {

        assertReason(
                Reason.UNSUPPORTED_SCHEMA_VERSION,
                tampered(
                        "schema-2",
                        "PRAGMA user_version = 2"
                )
        );
        assertReason(
                Reason.UNSUPPORTED_READER_VERSION,
                tampered(
                        "reader-2",
                        "UPDATE pack_meta SET value = '2' WHERE key = 'minReaderVersion'"
                )
        );
    }

    @Test
    @DisplayName("필수 메타데이터 누락 거부")
    void refusesMissingMeta() {

        assertReason(
                Reason.INVALID_META,
                tampered(
                        "missing-meta",
                        "DELETE FROM pack_meta WHERE key = 'createdBy'"
                )
        );
    }

    @Test
    @DisplayName("기본 variant 누락·rowid 공백·섹션 순환 내용 거부")
    void refusesInconsistentContent() {

        assertReason(
                Reason.INCONSISTENT_CONTENT,
                tampered(
                        "no-default-variant",
                        "INSERT INTO code_variants VALUES(1, 0, 'language', 'java', 0, 0, 'class A {}', '{}')"
                )
        );
        assertReason(
                Reason.INCONSISTENT_CONTENT,
                tampered(
                        "rowid-gap",
                        "UPDATE chunks SET rowid = 999 WHERE rowid = 2"
                )
        );
        assertReason(
                Reason.INCONSISTENT_CONTENT,
                tampered(
                        "section-cycle",
                        "UPDATE sections SET parent_section_id = section_id"
                )
        );
    }

    @Test
    @DisplayName("손상된 검색 색인 거부")
    void refusesBrokenSearchIndex() {

        // 논리 해시의 파생 색인 제외로 색인 손상은 MATCH 표본 검사로 탐지
        assertReason(
                Reason.SEARCH_INDEX_BROKEN,
                tampered(
                        "empty-index",
                        "INSERT INTO chunks_fts(chunks_fts) VALUES('delete-all')"
                )
        );
    }

    @Test
    @DisplayName("논리 해시와 일치하지 않는 내용 거부")
    void refusesContentThatDoesNotMatchLogicalHash() {

        assertReason(
                Reason.LOGICAL_HASH_MISMATCH,
                tampered(
                        "edited-content",
                        "UPDATE chunks SET content = 'tampered content' WHERE rowid = 1",
                        "INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')"
                )
        );
    }

    private PackWriteResult writeSample() {

        return TestPacks
                .builder()
                .document("howto/data-access")
                .chunk(
                        "Configure a DataSource with spring.datasource.url",
                        List
                                .of("spring.datasource.url")
                )
                .chunk("Use Transactional on the service method")
                .document("reference/web")
                .chunk("SecurityFilterChain bean configuration")
                .writeTo(directory);
    }

    /** 새로 만든 정상 Pack을 SQL로 망가뜨린 사본 */
    private Path tampered(
            String name,
            String... statements
    ) {

        try {
            Path source = Files
                    .createDirectory(directory
                            .resolve(name));
            Path pack = TestPacks
                    .builder()
                    .document("howto/data-access")
                    .chunk("Configure a DataSource")
                    .chunk("Use Transactional on the service method")
                    .document("reference/web")
                    .chunk("SecurityFilterChain bean configuration")
                    .writeTo(source)
                    .path();

            try (Connection connection = DriverManager
                    .getConnection("jdbc:sqlite:" + pack);
                 Statement statement = connection
                         .createStatement()) {
                for (String sql : statements) {
                    statement
                            .execute(sql);
                }
            }

            return pack;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertReason(
            Reason expected,
            Path pack
    ) {

        PackValidationException exception = assertThrows(
                PackValidationException.class,
                () -> PackReader
                        .open(pack)
                        .close()
        );

        assertEquals(
                expected,
                exception
                        .reason(),
                exception
                        .getMessage()
        );
    }
}
