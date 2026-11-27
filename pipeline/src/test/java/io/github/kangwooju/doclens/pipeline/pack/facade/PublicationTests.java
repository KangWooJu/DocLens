package io.github.kangwooju.doclens.pipeline.pack.facade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import javax.sql.DataSource;

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

import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.exception.PublicationConflictException;
import io.github.kangwooju.doclens.pipeline.pack.service.BuildService;
import io.github.kangwooju.doclens.pipeline.pack.service.PublicationService;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.testsupport.PackRequestFixtures;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("발행 번호 CAS와 publication manifest")
class PublicationTests {

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .build();

    @TempDir
    static Path objectRoot;

    @Autowired
    private PackBuildFacade facade;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private BuildService buildService;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

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
    @DisplayName("기대 번호가 맞으면 다음 번호, 낡은 번호는 Build를 SEALED로 두고 거절, 맞는 번호로 다시 보내면 같은 Pack 채택")
    void checksExpectedSequence() throws IOException {

        BuildResult first = facade
                .build(
                        request(1),
                        0L
                );
        BuildResult second = facade
                .build(
                        request(2),
                        1L
                );

        assertEquals(
                1L,
                sequence(first
                        .buildId())
        );
        assertEquals(
                2L,
                sequence(second
                        .buildId())
        );
        assertThrows(
                PublicationConflictException.class,
                () -> facade
                        .build(
                                request(3),
                                1L
                        )
        );

        UUID stale = buildId(request(3));
        Path pack = onlyPackFile(stale);
        FileTime written = Files
                .getLastModifiedTime(pack);

        assertEquals(
                "SEALED",
                status(stale)
        );
        assertNull(sequence(stale));

        BuildResult third = facade
                .build(
                        request(3),
                        2L
                );

        assertEquals(
                BuildStatus.SUCCEEDED,
                third
                        .status()
        );
        assertEquals(
                3L,
                sequence(stale)
        );
        assertEquals(
                pack,
                onlyPackFile(stale)
        );
        assertEquals(
                written,
                Files
                        .getLastModifiedTime(pack)
        );
        assertEquals(
                third,
                facade
                        .build(
                                request(3),
                                0L
                        )
        );
    }

    @Test
    @DisplayName("다른 Build 둘이 같은 기대 번호로 동시에 발행하면 하나만 성공, 번호 중복 없음")
    void publishesOneOfConcurrentBuilds() throws Exception {

        ExecutorService executor = Executors
                .newFixedThreadPool(2);

        try {
            List<Future<BuildResult>> futures = List
                    .of(
                            executor
                                    .submit(() -> facade
                                            .build(
                                                    request(1),
                                                    0L
                                            )),
                            executor
                                    .submit(() -> facade
                                            .build(
                                                    request(2),
                                                    0L
                                            ))
                    );
            List<Throwable> failures = new ArrayList<>();

            for (Future<BuildResult> future : futures) {
                try {
                    future
                            .get();
                } catch (ExecutionException exception) {
                    failures
                            .add(exception
                                    .getCause());
                }
            }

            assertEquals(
                    1,
                    failures
                            .size()
            );
            assertInstanceOf(
                    PublicationConflictException.class,
                    failures
                            .getFirst()
            );
            assertEquals(
                    List
                            .of(1L),
                    sequences()
            );
        } finally {
            executor
                    .shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 번호를 먼저 잡은 미commit 발행이 있으면 기대 번호 요청은 unique 위반 뒤 거절, Build는 SEALED")
    void rejectsUniqueViolationWithExpected() throws Exception {

        assertThrows(
                PublicationConflictException.class,
                () -> raceHeldPublication(0L)
        );

        UUID loser = buildId(request(2));

        assertEquals(
                "SEALED",
                status(loser)
        );
        assertEquals(
                List
                        .of(1L),
                sequences()
        );
    }

    @Test
    @DisplayName("기대 번호 없는 발행은 unique 위반을 경합으로 보고 한 번 다시 시도해 다음 번호")
    void retriesUniqueViolationWithoutExpected() throws Exception {

        BuildResult result = raceHeldPublication(null);

        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertEquals(
                2L,
                sequence(result
                        .buildId())
        );
    }

    @Test
    @DisplayName("manifest는 계약 필드 전부와 행 값, 지운 뒤 다시 만들면 같은 bytes")
    void rebuildsManifest() throws IOException {

        BuildResult result = facade
                .build(request(1));
        BuildRow published = row(result
                .buildId());
        ObjectRef ref = publicationService
                .ensureManifest(published);
        Path file = objectRoot
                .resolve(ref
                        .objectKey());
        byte[] bytes = Files
                .readAllBytes(file);
        Map<String, Object> manifest = JSON
                .readValue(
                        bytes,
                        new TypeReference<>() {
                        }
                );

        assertEquals(
                "packs/spring-boot-4.1.2/publications/1.json",
                ref
                        .objectKey()
        );
        assertEquals(
                bytes.length,
                ref
                        .sizeBytes()
        );
        assertEquals(
                requiredFields(),
                manifest
                        .keySet()
        );
        assertEquals(
                "document:spring-boot:4.1.2",
                manifest
                        .get("channelKey")
        );
        assertEquals(
                1,
                manifest
                        .get("publicationSequence")
        );
        assertEquals(
                result
                        .buildId()
                        .toString(),
                manifest
                        .get("buildId")
        );
        assertEquals(
                published
                        .pack()
                        .objectKey(),
                manifest
                        .get("objectKey")
        );
        assertEquals(
                published
                        .pack()
                        .checksum(),
                manifest
                        .get("checksum")
        );
        assertEquals(
                List
                        .of("4.1.2"),
                manifest
                        .get("supportedVersions")
        );
        assertEquals(
                List
                        .of(
                                "snap-f16-sections-tabs-table-links.html",
                                "snap-reference.html"
                        ),
                manifest
                        .get("sourceSnapshotIds")
        );
        assertTrue(((String) manifest
                .get("publishedAt"))
                .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z"));
        assertEquals(
                new String(
                        bytes,
                        StandardCharsets.UTF_8
                ),
                publicationService
                        .manifest(published)
        );

        Files
                .delete(file);

        assertEquals(
                ref,
                publicationService
                        .ensureManifest(row(result
                                .buildId()))
        );
        assertArrayEquals(
                bytes,
                Files
                        .readAllBytes(file)
        );
    }

    // Build 둘을 봉인하고 첫째의 같은 번호 발행을 미commit으로 잡은 채 둘째를 expected로 build, 둘째가 잠금 대기에 들어간 뒤 첫째 commit
    private BuildResult raceHeldPublication(Long expected) throws Exception {

        UUID holder = facade
                .ingest(request(1))
                .buildId();

        facade
                .ingest(request(2));

        ExecutorService executor = Executors
                .newSingleThreadExecutor();

        try (Connection connection = dataSource
                .getConnection()) {
            connection
                    .setAutoCommit(false);

            try (PreparedStatement statement = connection
                    .prepareStatement("""
                            UPDATE pipeline.builds
                            SET status = 'SUCCEEDED',
                                pack_object_key = 'packs/spring-boot-4.1.2/held/a.pack',
                                pack_checksum = 'sha256:' || repeat('e', 64),
                                pack_size_bytes = 10,
                                pack_logical_sha256 = 'sha256:' || repeat('f', 64),
                                publication_sequence = 1,
                                published_at = clock_timestamp()
                            WHERE build_id = ?
                            """)) {
                statement
                        .setObject(
                                1,
                                holder
                        );
                statement
                        .executeUpdate();
            }

            Future<BuildResult> loser = executor
                    .submit(() -> facade
                            .build(
                                    request(2),
                                    expected
                            ));

            awaitLockWait();
            connection
                    .commit();

            try {
                return loser
                        .get();
            } catch (ExecutionException exception) {
                if (exception
                        .getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }

                throw exception;
            }
        } finally {
            executor
                    .shutdownNow();
        }
    }

    // 다른 session이 publication_sequence UPDATE에서 잠금을 기다릴 때까지 대기, 30초 넘으면 실패
    private void awaitLockWait() throws InterruptedException {

        for (int attempt = 0; attempt < 300; attempt++) {
            int waiting = jdbcClient
                    .sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query LIKE '%publication_sequence%'
                            """)
                    .query(Integer.class)
                    .single();

            if (waiting > 0) {
                return;
            }

            Thread
                    .sleep(100);
        }

        throw new IllegalStateException("no session waited on the held publication");
    }

    // runInputSnapshotHash만 다른 원본 두 개의 요청
    private static BuildRequest request(int run) {

        BuildRequest base = PackRequestFixtures
                .request(PackRequestFixtures
                        .sources());

        return new BuildRequest(
                base
                        .project(),
                base
                        .exactVersion(),
                base
                        .applicability(),
                base
                        .inputScopeKey(),
                "sha256:" + Integer
                        .toString(run)
                        .repeat(64),
                base
                        .processingConfigHash(),
                base
                        .modelChecksum(),
                base
                        .sources(),
                base
                        .includes()
        );
    }

    // 요청의 Build, ingest는 이미 있는 Build를 그대로 돌려줌
    private UUID buildId(BuildRequest request) {

        return jdbcClient
                .sql("SELECT build_id FROM pipeline.builds WHERE run_input_snapshot_hash = ?")
                .param(request
                        .runInputSnapshotHash())
                .query(UUID.class)
                .single();
    }

    // Build 행
    private BuildRow row(UUID buildId) {

        return transactionExecutor
                .write(() -> buildService
                        .exportTarget(buildId)
                        .build());
    }

    // Build 상태
    private String status(UUID buildId) {

        return jdbcClient
                .sql("SELECT status FROM pipeline.builds WHERE build_id = ?")
                .param(buildId)
                .query(String.class)
                .single();
    }

    // Build 발행 번호, 없으면 null
    private Long sequence(UUID buildId) {

        return jdbcClient
                .sql("SELECT publication_sequence FROM pipeline.builds WHERE build_id = ?")
                .param(buildId)
                .query((rs, row) -> rs
                        .getObject(
                                "publication_sequence",
                                Long.class
                        ))
                .list()
                .getFirst();
    }

    // 정해진 발행 번호 전체, 오름차순
    private List<Long> sequences() {

        return jdbcClient
                .sql("SELECT publication_sequence FROM pipeline.builds WHERE publication_sequence IS NOT NULL ORDER BY 1")
                .query(Long.class)
                .list();
    }

    // Build 디렉터리의 유일한 게시 Pack 파일
    private static Path onlyPackFile(UUID buildId) throws IOException {

        try (Stream<Path> files = Files
                .list(objectRoot
                        .resolve("packs/spring-boot-4.1.2/" + buildId))) {
            List<Path> packs = files
                    .filter(file -> file
                            .getFileName()
                            .toString()
                            .matches("[0-9a-f]{64}\\.pack"))
                    .toList();

            assertEquals(
                    1,
                    packs
                            .size()
            );

            return packs
                    .getFirst();
        }
    }

    // contracts/v1 publication-manifest의 필수 필드, 정의된 필드 전체와 같아야 함
    @SuppressWarnings("unchecked")
    private static Set<String> requiredFields() throws IOException {

        Map<String, Object> schema = JSON
                .readValue(
                        Path
                                .of(System
                                        .getProperty("doclens.contractsDir"))
                                .resolve("publication-manifest.schema.json")
                                .toFile(),
                        new TypeReference<>() {
                        }
                );
        Set<String> required = new HashSet<>((List<String>) schema
                .get("required"));

        assertEquals(
                ((Map<String, Object>) schema
                        .get("properties"))
                        .keySet(),
                required
        );

        return required;
    }
}
