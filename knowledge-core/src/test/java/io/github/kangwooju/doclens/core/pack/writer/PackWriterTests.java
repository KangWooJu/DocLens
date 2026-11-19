package io.github.kangwooju.doclens.core.pack.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.dto.CodeVariant;
import io.github.kangwooju.doclens.core.pack.dto.PackChunk;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackSection;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.exception.PackWriteException;
import io.github.kangwooju.doclens.core.pack.fixture.TestPacks;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;

@DisplayName("Pack 쓰기")
class PackWriterTests {

    @TempDir
    Path directory;

    @Test
    @DisplayName("검증된 Pack 파일 생성")
    void writesVerifiedPackFile() throws Exception {

        PackWriteResult result = TestPacks
                .builder()
                .document("howto/data-access")
                .chunk(
                        "Configure a DataSource with spring.datasource.url",
                        List
                                .of("spring.datasource.url")
                )
                .chunk("Use @Transactional on the service method")
                .document("reference/web")
                .chunk("SecurityFilterChain bean configuration")
                .writeTo(directory);

        assertEquals(
                directory
                        .resolve("test.pack"),
                result
                        .path()
        );
        assertEquals(
                Files
                        .size(result
                                .path()),
                result
                        .sizeBytes()
        );
        assertEquals(
                sha256(result
                        .path()),
                result
                        .checksum()
        );
        assertTrue(result
                .logicalContentSha256()
                .startsWith("sha256:"));

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

        try (Connection connection = open(result
                .path());
             Statement statement = connection
                     .createStatement()) {

            assertEquals(
                    1397771083L,
                    single(
                            statement,
                            "PRAGMA application_id"
                    )
            );
            assertEquals(
                    1L,
                    single(
                            statement,
                            "PRAGMA user_version"
                    )
            );
            assertEquals(
                    17L,
                    single(
                            statement,
                            "SELECT count(*) FROM pack_meta"
                    )
            );
            assertEquals(
                    2L,
                    single(
                            statement,
                            "SELECT count(*) FROM documents"
                    )
            );
            assertEquals(
                    3L,
                    single(
                            statement,
                            "SELECT count(*) FROM chunks"
                    )
            );
            assertEquals(
                    1L,
                    single(
                            statement,
                            "SELECT count(*) FROM chunks_fts WHERE chunks_fts MATCH '\"SecurityFilterChain\"'"
                    )
            );
            assertEquals(
                    1L,
                    single(
                            statement,
                            "SELECT count(*) FROM identifiers WHERE identifier = 'spring.datasource.url'"
                    )
            );

            // (documentId, section ordinal, chunk ordinal) 순서의 rowid 1..N
            List<String> documentsByRowid = new ArrayList<>();

            try (ResultSet rows = statement
                    .executeQuery("SELECT rowid, document_id FROM chunks ORDER BY rowid")) {
                long expectedRowid = 1;

                while (rows
                        .next()) {
                    assertEquals(
                            expectedRowid++,
                            rows
                                    .getLong(1)
                    );
                    documentsByRowid
                            .add(rows
                                    .getString(2));
                }
            }

            List<String> sorted = new ArrayList<>(documentsByRowid);
            sorted
                    .sort(String::compareTo);
            assertEquals(
                    sorted,
                    documentsByRowid
            );

            try (ResultSet rows = statement
                    .executeQuery("SELECT value FROM pack_meta WHERE key = 'embedding'")) {
                rows
                        .next();
                assertEquals(
                        "null",
                        rows
                                .getString(1)
                );
            }
        }
    }

    @Test
    @DisplayName("buildId와 무관하고 내용을 따르는 논리 해시")
    void logicalHashIgnoresBuildIdButFollowsContent() {

        PackWriteResult first = TestPacks
                .builder()
                .buildId("build-a")
                .document("a")
                .chunk("same content")
                .writeTo(subdirectory("first"));
        PackWriteResult second = TestPacks
                .builder()
                .buildId("build-b")
                .document("a")
                .chunk("same content")
                .writeTo(subdirectory("second"));
        PackWriteResult changed = TestPacks
                .builder()
                .buildId("build-a")
                .document("a")
                .chunk("different content")
                .writeTo(subdirectory("changed"));

        assertEquals(
                first
                        .logicalContentSha256(),
                second
                        .logicalContentSha256()
        );
        assertNotEquals(
                first
                        .checksum(),
                second
                        .checksum()
        );
        assertNotEquals(
                first
                        .logicalContentSha256(),
                changed
                        .logicalContentSha256()
        );
    }

    @Test
    @DisplayName("순서가 어긋난 문서 거절과 부분 파일 삭제")
    void rejectsDocumentsOutOfOrderAndDeletesPartialFile() {

        List<PackDocument> documents = TestPacks
                .builder()
                .document("a")
                .chunk("one")
                .document("b")
                .chunk("two")
                .documents();
        Path target = directory
                .resolve("out-of-order.pack");

        PackWriter writer = PackWriter
                .create(
                        target,
                        TestPacks
                                .meta("build")
                );
        writer
                .add(documents
                        .get(1));

        assertThrows(
                PackWriteException.class,
                () -> writer
                        .add(documents
                                .getFirst())
        );
        assertFalse(Files
                .exists(target));
    }

    @Test
    @DisplayName("문서 간 중복 섹션 ID 거절")
    void rejectsDuplicateSectionIdAcrossDocuments() {

        PackDocument first = document(
                "doc-1",
                List
                        .of(section(
                                "shared-intro",
                                null,
                                0
                        ))
        );
        PackDocument second = document(
                "doc-2",
                List
                        .of(section(
                                "shared-intro",
                                null,
                                0
                        ))
        );

        assertThrows(
                PackWriteException.class,
                () -> writeAll(
                        "duplicate-section.pack",
                        List
                                .of(
                                        first,
                                        second
                                )
                )
        );
    }

    @Test
    @DisplayName("뒤에 선언된 부모 섹션 참조 거절")
    void rejectsParentDeclaredLater() {

        PackDocument document = document(
                "doc",
                List
                        .of(
                                section(
                                        "child",
                                        "parent",
                                        0
                                ),
                                section(
                                        "parent",
                                        null,
                                        1
                                )
                        )
        );

        assertThrows(
                PackWriteException.class,
                () -> writeAll(
                        "parent-later.pack",
                        List
                                .of(document)
                )
        );
    }

    @Test
    @DisplayName("기본 variant가 정확히 하나가 아닌 코드 블록 거절")
    void rejectsCodeBlockWithoutExactlyOneDefault() {

        PackChunk chunk = new PackChunk(
                TestPacks
                        .id("chunk:variants"),
                0,
                "Build configuration",
                "build",
                "[]",
                "{}",
                List
                        .of(
                                variant(
                                        "maven",
                                        false,
                                        0
                                ),
                                variant(
                                        "gradle",
                                        false,
                                        1
                                )
                        ),
                List
                        .of(),
                List
                        .of()
        );
        PackDocument document = document(
                "doc",
                List
                        .of(new PackSection(
                                "section",
                                null,
                                null,
                                List
                                        .of("Build"),
                                0,
                                List
                                        .of(chunk)
                        ))
        );

        assertThrows(
                PackWriteException.class,
                () -> writeAll(
                        "no-default.pack",
                        List
                                .of(document)
                )
        );
    }

    @Test
    @DisplayName("다른 문서 버전의 문서 거절")
    void rejectsDocumentFromAnotherVersion() {

        PackDocument document = new PackDocument(
                TestPacks
                        .id("document:old"),
                "https://docs.spring.io/old.html",
                "old",
                DocumentType.REFERENCE,
                "3.5.0",
                TestPacks
                        .id("source-snapshot"),
                TestPacks
                        .id("raw:old"),
                "{}",
                List
                        .of()
        );

        assertThrows(
                PackWriteException.class,
                () -> writeAll(
                        "wrong-version.pack",
                        List
                                .of(document)
                )
        );
    }

    @Test
    @DisplayName("잘못된 ID 형식과 실수가 든 JSON 컬럼 거절")
    void rejectsInvalidIdsAndJsonColumns() {

        assertThrows(
                PackWriteException.class,
                () -> new PackChunk(
                        "chunk-1",
                        0,
                        "content",
                        "path",
                        "[]",
                        "{}",
                        List
                                .of(),
                        List
                                .of(),
                        List
                                .of()
                )
        );

        PackDocument floatSourceMap = new PackDocument(
                TestPacks
                        .id("document:float"),
                "https://docs.spring.io/float.html",
                "float",
                DocumentType.REFERENCE,
                TestPacks.VERSION,
                TestPacks
                        .id("source-snapshot"),
                TestPacks
                        .id("raw:float"),
                "{\"offset\":1.5}",
                List
                        .of()
        );

        assertThrows(
                PackWriteException.class,
                () -> writeAll(
                        "float-json.pack",
                        List
                                .of(floatSourceMap)
                )
        );
    }

    @Test
    @DisplayName("기존 대상 파일 거부와 조기 종료 시 파일 정리")
    void refusesExistingTargetAndCleansUpWhenClosedEarly() throws Exception {

        Path existing = Files
                .createFile(directory
                        .resolve("existing.pack"));

        assertThrows(
                PackWriteException.class,
                () -> PackWriter
                        .create(
                                existing,
                                TestPacks
                                        .meta("build")
                        )
        );

        Path abandoned = directory
                .resolve("abandoned.pack");

        try (PackWriter writer = PackWriter
                .create(
                        abandoned,
                        TestPacks
                                .meta("build")
                )) {
            assertTrue(Files
                    .exists(abandoned));
        }

        assertFalse(Files
                .exists(abandoned));
    }

    // 문서 목록 전체의 Pack 쓰기
    private void writeAll(
            String fileName,
            List<PackDocument> documents
    ) {

        try (PackWriter writer = PackWriter
                .create(
                        directory
                                .resolve(fileName),
                        TestPacks
                                .meta("build")
                )) {
            documents
                    .forEach(writer::add);
            writer
                    .finish();
        }
    }

    // 임시 디렉터리 아래 하위 디렉터리 생성
    private Path subdirectory(String name) {

        try {
            return Files
                    .createDirectory(directory
                            .resolve(name));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    // 시험용 PackDocument 생성
    private static PackDocument document(
            String key,
            List<PackSection> sections
    ) {

        return new PackDocument(
                TestPacks
                        .id("document:" + key),
                "https://docs.spring.io/" + key + ".html",
                key,
                DocumentType.REFERENCE,
                TestPacks.VERSION,
                TestPacks
                        .id("source-snapshot"),
                TestPacks
                        .id("raw:" + key),
                "{}",
                sections
        );
    }

    // 시험용 PackSection 생성
    private static PackSection section(
            String sectionId,
            String parentSectionId,
            int ordinal
    ) {

        return new PackSection(
                sectionId,
                parentSectionId,
                null,
                List
                        .of(sectionId),
                ordinal,
                List
                        .of()
        );
    }

    // 시험용 CodeVariant 생성
    private static CodeVariant variant(
            String name,
            boolean isDefault,
            int ordinal
    ) {

        return new CodeVariant(
                0,
                VariantGroupType.BUILD_TOOL,
                name,
                ordinal,
                isDefault,
                "<build/>",
                "{}"
        );
    }

    // Pack 파일 SQLite 연결
    private static Connection open(Path pack) throws SQLException {

        return DriverManager
                .getConnection("jdbc:sqlite:" + pack);
    }

    // 단일 값 조회
    private static long single(
            Statement statement,
            String sql
    ) throws SQLException {

        try (ResultSet rows = statement
                .executeQuery(sql)) {
            rows
                    .next();
            return rows
                    .getLong(1);
        }
    }

    // 파일 전체 SHA-256 계산
    private static String sha256(Path path) throws Exception {

        return "sha256:" + HexFormat
                .of()
                .formatHex(MessageDigest
                        .getInstance("SHA-256")
                        .digest(Files
                                .readAllBytes(path)));
    }
}
