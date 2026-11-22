package io.github.kangwooju.doclens.pipeline.pack.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.reader.PackReader;
import io.github.kangwooju.doclens.pipeline.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.ExportTarget;
import io.github.kangwooju.doclens.pipeline.pack.dto.PackRecord;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildArtifactMissingException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNotExportableException;
import io.github.kangwooju.doclens.pipeline.pack.service.BuildService;
import io.github.kangwooju.doclens.pipeline.pack.service.PackExportService;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.testsupport.PackRequestFixtures;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("봉인된 Build의 Pack Export와 게시")
class ExportTests {

    @TempDir
    static Path objectRoot;

    @Autowired
    private PackBuildFacade facade;

    @Autowired
    private BuildService buildService;

    @Autowired
    private PackExportService packExportService;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private JdbcClient jdbcClient;

    // 시험 object root 지정
    @DynamicPropertySource
    static void objectRoot(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.pipeline.object-root",
                        () -> objectRoot
                                .toString()
                );
    }

    // 시험마다 표와 파일 비우고 원본 두 개 쓰기
    @BeforeEach
    void prepare() throws IOException {

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();

        try (Stream<Path> files = Files
                .walk(objectRoot)) {
            for (Path path : files
                    .sorted((left, right) -> right
                            .compareTo(left))
                    .toList()) {
                if (!path
                        .equals(objectRoot)) {
                    Files
                            .delete(path);
                }
            }
        }

        for (SourceEntry source : PackRequestFixtures
                .sources()) {
            PackRequestFixtures
                    .writeRaw(
                            objectRoot,
                            source
                    );
        }
    }

    @Test
    @DisplayName("build는 checksum 이름의 Pack 하나를 게시, Reader 검증과 메타 순서 통과")
    void publishesPack() throws Exception {

        BuildResult result = facade
                .build(request());
        PackRecord pack = result
                .pack();
        Path file = objectRoot
                .resolve(pack
                        .objectKey());

        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertEquals(
                "packs/spring-boot-4.1.2/" + result
                        .buildId() + "/" + pack
                                .checksum()
                                .substring(7) + ".pack",
                pack
                        .objectKey()
        );
        assertEquals(
                pack
                        .checksum(),
                sha256(file)
        );
        assertEquals(
                List
                        .of(file),
                packFiles(result
                        .buildId())
        );

        try (PackReader reader = PackReader
                .open(file)) {
            assertEquals(
                    result
                            .buildId()
                            .toString(),
                    reader
                            .info()
                            .buildId()
            );
            assertEquals(
                    CanonicalJson
                            .write(List
                                    .of(
                                            "snap-f16-sections-tabs-table-links.html",
                                            "snap-reference.html"
                                    )),
                    meta(
                            reader,
                            "sourceSnapshotIds"
                    )
            );
        }

        assertEquals(
                List
                        .of(
                                "CHUNK",
                                "EXPORT",
                                "PARSE"
                        ),
                jdbcClient
                        .sql("SELECT stage FROM pipeline.verified_stage_results ORDER BY stage")
                        .query(String.class)
                        .list()
        );
    }

    @Test
    @DisplayName("두 번째 export는 같은 기록, 파일 하나")
    void exportsIdempotently() {

        BuildResult first = facade
                .build(request());
        BuildResult second = facade
                .export(first
                        .buildId());

        assertEquals(
                first
                        .pack(),
                second
                        .pack()
        );
        assertEquals(
                1,
                packFiles(first
                        .buildId())
                        .size()
        );
    }

    @Test
    @DisplayName("게시까지 하고 기록 전에 멈춘 Pack은 다음 export가 새로 쓰지 않고 채택")
    void adoptsPublishedPack() {

        UUID buildId = facade
                .ingest(request())
                .buildId();
        ExportTarget target = transactionExecutor
                .write(() -> buildService
                        .exportTarget(buildId));
        PackRecord published = packExportService
                .publish(
                        target
                                .build(),
                        packExportService
                                .write(target)
                );
        BuildResult result = facade
                .export(buildId);

        assertEquals(
                published,
                result
                        .pack()
        );
        assertEquals(
                1,
                packFiles(buildId)
                        .size()
        );
    }

    @Test
    @DisplayName("남은 임시 파일과 깨진 Pack이 있어도 Export 성공, 깨진 Pack은 정리")
    void ignoresLeftoversAndPrunes() throws IOException {

        UUID buildId = facade
                .ingest(request())
                .buildId();
        Path directory = objectRoot
                .resolve("packs/spring-boot-4.1.2/" + buildId);
        Path broken = directory
                .resolve("0"
                        .repeat(64) + ".pack");
        Path temporary = directory
                .resolve(".left.pack.tmp");

        Files
                .createDirectories(directory);
        Files
                .writeString(
                        broken,
                        "not a pack"
                );
        Files
                .writeString(
                        temporary,
                        "partial"
                );

        BuildResult result = facade
                .export(buildId);

        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertFalse(Files
                .exists(broken));
        assertTrue(Files
                .exists(temporary));
        assertEquals(
                1,
                packFiles(buildId)
                        .size()
        );
    }

    @Test
    @DisplayName("RUNNING은 그대로, 보류와 실패와 없는 Build는 Export 거부")
    void checksExportableStatus() throws IOException {

        Files
                .delete(objectRoot
                        .resolve(PackRequestFixtures
                                .sources()
                                .getLast()
                                .rawObjectKey()));

        UUID running = facade
                .ingest(request())
                .buildId();

        assertEquals(
                BuildStatus.RUNNING,
                facade
                        .export(running)
                        .status()
        );

        jdbcClient
                .sql("UPDATE pipeline.builds SET status = 'FAILED', failure_reason = 'test' WHERE build_id = ?")
                .param(running)
                .update();

        assertThrows(
                BuildNotExportableException.class,
                () -> facade
                        .export(running)
        );
        assertThrows(
                BuildNotExportableException.class,
                () -> facade
                        .export(UUID
                                .randomUUID())
        );
    }

    @Test
    @DisplayName("SUCCEEDED인데 기록한 파일이 없으면 알림, 추가 Pack은 다음 export가 정리")
    void verifiesRecordedPack() throws IOException {

        BuildResult result = facade
                .build(request());
        Path recorded = objectRoot
                .resolve(result
                        .pack()
                        .objectKey());
        Path extra = recorded
                .resolveSibling("f"
                        .repeat(64) + ".pack");

        Files
                .copy(
                        recorded,
                        extra
                );
        facade
                .export(result
                        .buildId());

        assertFalse(Files
                .exists(extra));

        Files
                .delete(recorded);

        assertThrows(
                BuildArtifactMissingException.class,
                () -> facade
                        .export(result
                                .buildId())
        );
    }

    @Test
    @DisplayName("두 스레드가 같은 요청으로 build하면 Build 하나, 게시 Pack 하나")
    void convergesConcurrentBuilds() throws Exception {

        ExecutorService executor = Executors
                .newFixedThreadPool(2);

        try {
            Future<BuildResult> first = executor
                    .submit(() -> facade
                            .build(request()));
            Future<BuildResult> second = executor
                    .submit(() -> facade
                            .build(request()));
            BuildResult left = first
                    .get();
            BuildResult right = second
                    .get();

            assertEquals(
                    left
                            .buildId(),
                    right
                            .buildId()
            );
            assertEquals(
                    1,
                    jdbcClient
                            .sql("SELECT count(*) FROM pipeline.builds")
                            .query(Integer.class)
                            .single()
            );

            BuildResult settled = facade
                    .export(left
                            .buildId());

            assertEquals(
                    BuildStatus.SUCCEEDED,
                    settled
                            .status()
            );
            assertEquals(
                    1,
                    packFiles(left
                            .buildId())
                            .size()
            );
        } finally {
            executor
                    .shutdownNow();
        }
    }

    // 원본 두 개의 요청
    private static BuildRequest request() {

        return PackRequestFixtures
                .request(PackRequestFixtures
                        .sources());
    }

    // Build 디렉터리의 게시 Pack 파일
    private static List<Path> packFiles(UUID buildId) {

        Path directory = objectRoot
                .resolve("packs/spring-boot-4.1.2/" + buildId);

        try (Stream<Path> files = Files
                .list(directory)) {
            return files
                    .filter(file -> file
                            .getFileName()
                            .toString()
                            .matches("[0-9a-f]{64}\\.pack"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // pack_meta 값
    private static String meta(
            PackReader reader,
            String key
    ) throws Exception {

        return reader
                .query(connection -> {
                    try (PreparedStatement statement = connection
                            .prepareStatement("SELECT value FROM pack_meta WHERE key = ?")) {
                        statement
                                .setString(
                                        1,
                                        key
                                );

                        try (ResultSet resultSet = statement
                                .executeQuery()) {
                            return resultSet
                                    .next() ? resultSet
                                            .getString(1) : null;
                        }
                    }
                });
    }

    // 파일 bytes의 sha256: 접두 hex
    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {

        return "sha256:" + HexFormat
                .of()
                .formatHex(MessageDigest
                        .getInstance("SHA-256")
                        .digest(Files
                                .readAllBytes(file)));
    }
}
