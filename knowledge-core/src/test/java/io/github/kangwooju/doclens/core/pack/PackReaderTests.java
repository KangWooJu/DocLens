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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.PackValidationException.Reason;

class PackReaderTests {

    @TempDir
    Path directory;

    @Test
    void opensValidPackWithoutChangingIt() {

        PackWriteResult written = writeSample();

        try (PackReader reader = PackReader.open(written.path())) {
            PackInfo info = reader.info();

            assertEquals("spring-boot", info.project());
            assertEquals(TestPacks.VERSION, info.docVersion());
            assertEquals(1, info.packSchemaVersion());
            assertEquals(1, info.minReaderVersion());
            assertEquals(written.logicalContentSha256(), info.logicalContentSha256());
            assertEquals(List.of("BM25", "SECTIONS", "VARIANTS"), info.capabilities());
            assertEquals(2, info.documentCount());
            assertEquals(3, info.chunkCount());
        }

        assertEquals(
                written.checksum(),
                PackFiles.sha256(written.path())
        );

        for (String suffix : List.of("-journal", "-wal", "-shm")) {
            assertFalse(Files.exists(directory.resolve("test.pack" + suffix)));
        }
    }

    @Test
    void refusesSymbolicLink() throws Exception {

        PackWriteResult written = writeSample();
        Path link = Files.createSymbolicLink(
                directory.resolve("link.pack"),
                written.path()
        );

        assertReason(
                Reason.NOT_A_FILE,
                link
        );
    }

    @Test
    void refusesDatabaseThatIsNotAPack() throws Exception {

        Path other = directory.resolve("other.db");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + other);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE notes(text TEXT)");
        }

        assertReason(
                Reason.NOT_A_PACK,
                other
        );
    }

    @Test
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
    void refusesBrokenSearchIndex() {

        // 논리 해시는 파생 색인을 보지 않으므로 색인 손상은 MATCH 표본이 잡는다.
        assertReason(
                Reason.SEARCH_INDEX_BROKEN,
                tampered(
                        "empty-index",
                        "INSERT INTO chunks_fts(chunks_fts) VALUES('delete-all')"
                )
        );
    }

    @Test
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
                        List.of("spring.datasource.url")
                )
                .chunk("Use Transactional on the service method")
                .document("reference/web")
                .chunk("SecurityFilterChain bean configuration")
                .writeTo(directory);
    }

    /** 정상 Pack을 복사한 뒤 SQL로 망가뜨린 사본. */
    private Path tampered(
            String name,
            String... statements
    ) {

        try {
            Path source = Files.createDirectory(directory.resolve(name));
            Path pack = TestPacks
                    .builder()
                    .document("howto/data-access")
                    .chunk("Configure a DataSource")
                    .chunk("Use Transactional on the service method")
                    .document("reference/web")
                    .chunk("SecurityFilterChain bean configuration")
                    .writeTo(source)
                    .path();

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + pack);
                 Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
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
                exception.reason(),
                exception.getMessage()
        );
    }
}
