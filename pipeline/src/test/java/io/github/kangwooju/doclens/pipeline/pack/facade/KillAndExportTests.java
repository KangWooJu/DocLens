package io.github.kangwooju.doclens.pipeline.pack.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

import com.zaxxer.hikari.HikariDataSource;

import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackMeta;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.reader.PackReader;
import io.github.kangwooju.doclens.core.pack.writer.PackWriter;
import io.github.kangwooju.doclens.pipeline.pack.BuildFixtures;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.testsupport.IngestThenWaitMain;
import io.github.kangwooju.doclens.testsupport.PackRequestFixtures;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

/**
 * 구조 산출물 영속 완료 조건: CHUNK 뒤 kill, 다른 프로세스가 원본 없이 Export
 * 기준 Pack, PG 없이 만든 Pack, kill 뒤 Export한 Pack의 논리 내용과 행이 같아야 함
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("CHUNK 뒤 kill한 Build를 다른 프로세스가 Export")
class KillAndExportTests {

    private static final List<String> ROW_QUERIES = List
            .of(
                    "SELECT section_id || '|' || COALESCE(parent_section_id, '') || '|' || heading_path || '|' || ordinal FROM sections ORDER BY section_id",
                    "SELECT chunk_id || '|' || blocks_json || '|' || source_map_json FROM chunks ORDER BY chunk_id",
                    "SELECT chunk_rowid || '|' || block_index || '|' || variant || '|' || is_default || '|' || code FROM code_variants ORDER BY chunk_rowid, block_index, ordinal",
                    "SELECT target_url || '|' || COALESCE(target_anchor, '') FROM doc_links ORDER BY 1",
                    "SELECT document_id || '|' || url || '|' || raw_sha256 || '|' || source_snapshot_id || '|' || source_map_json FROM documents ORDER BY document_id"
            );

    @TempDir
    static Path objectRoot;

    @TempDir
    Path scratch;

    @Autowired
    private PackBuildFacade facade;

    @Autowired
    private DataSource dataSource;

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

    // 시험마다 표와 파일 비우기
    @BeforeEach
    void clean() throws IOException {

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();
        deleteUnder(objectRoot);
    }

    @Test
    @DisplayName("봉인 뒤 kill된 Build를 원본을 지운 뒤 Export하면 기준 Pack, 직접 Pack과 논리 내용과 행이 같음")
    void exportsAfterKillWithoutSources() throws Exception {

        writeAllRaw();

        BuildResult reference = facade
                .build(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()));
        Path referencePack = copyOut(reference);
        String direct = directLogicalSha(reference);

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();
        deleteUnder(objectRoot);
        writeAllRaw();

        String[] child = runChildUntilIngested();

        assertEquals(
                "SEALED",
                child[0]
        );
        assertEquals(
                BuildFixtures
                        .currentHash(),
                child[2]
        );

        deleteUnder(objectRoot
                .resolve("raw"));

        BuildResult exported = facade
                .export(UUID
                        .fromString(child[1]));

        assertEquals(
                BuildStatus.SUCCEEDED,
                exported
                        .status()
        );
        assertNotEquals(
                reference
                        .buildId(),
                exported
                        .buildId()
        );
        assertEquals(
                reference
                        .pack()
                        .logicalContentSha256(),
                exported
                        .pack()
                        .logicalContentSha256()
        );
        assertEquals(
                reference
                        .pack()
                        .logicalContentSha256(),
                direct
        );

        Path exportedPack = objectRoot
                .resolve(exported
                        .pack()
                        .objectKey());

        for (String query : ROW_QUERIES) {
            assertEquals(
                    rows(
                            referencePack,
                            query
                    ),
                    rows(
                            exportedPack,
                            query
                    ),
                    query
            );
        }
    }

    @Test
    @DisplayName("둘째 원본이 없어 RUNNING인 채 kill된 Build는 파일을 넣고 같은 요청을 보내면 같은 논리 내용으로 성공")
    void resumesAfterKillWithMissingSource() throws Exception {

        writeAllRaw();

        BuildResult reference = facade
                .build(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()));

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();
        deleteUnder(objectRoot);

        List<SourceEntry> sources = PackRequestFixtures
                .sources();

        PackRequestFixtures
                .writeRaw(
                        objectRoot,
                        sources
                                .getFirst()
                );

        String[] child = runChildUntilIngested();

        assertEquals(
                "RUNNING",
                child[0]
        );
        assertEquals(
                1,
                jdbcClient
                        .sql("SELECT count(*) FROM pipeline.document_artifacts")
                        .query(Integer.class)
                        .single()
        );

        PackRequestFixtures
                .writeRaw(
                        objectRoot,
                        sources
                                .getLast()
                );

        BuildResult resumed = facade
                .build(PackRequestFixtures
                        .request(sources));

        assertEquals(
                BuildStatus.SUCCEEDED,
                resumed
                        .status()
        );
        assertEquals(
                reference
                        .pack()
                        .logicalContentSha256(),
                resumed
                        .pack()
                        .logicalContentSha256()
        );
    }

    // 자식 JVM으로 ingest하고 결과 줄을 받은 뒤 SIGKILL, [상태, buildId, 처리 설정 hash]
    private String[] runChildUntilIngested() throws Exception {

        HikariDataSource hikari = (HikariDataSource) dataSource;
        String classpath = System
                .getProperty("doclens.test.pipelineClasspath");

        assertNotNull(
                classpath,
                "doclens.test.pipelineClasspath is set by pipeline/build.gradle.kts"
        );

        Process process = new ProcessBuilder(
                Path
                        .of(
                                System
                                        .getProperty("java.home"),
                                "bin",
                                "java"
                        )
                        .toString(),
                "-cp",
                classpath,
                IngestThenWaitMain.class
                        .getName(),
                "--spring.datasource.url=" + hikari
                        .getJdbcUrl(),
                "--spring.datasource.username=" + hikari
                        .getUsername(),
                "--spring.datasource.password=" + hikari
                        .getPassword(),
                "--doclens.pipeline.object-root=" + objectRoot,
                "--logging.level.root=WARN"
        )
                .redirectErrorStream(true)
                .start();
        List<String> output = new ArrayList<>();
        CompletableFuture<String> line = CompletableFuture
                .supplyAsync(() -> readUntilResult(
                        process,
                        output
                ));

        try {
            String result = line
                    .get(
                            120,
                            TimeUnit.SECONDS
                    );

            assertTrue(
                    result != null && !result
                            .startsWith(IngestThenWaitMain.PREFIX + "ERROR"),
                    String
                            .join(
                                    "\n",
                                    output
                            )
            );

            return result
                    .substring(IngestThenWaitMain.PREFIX
                            .length())
                    .split(" ");
        } finally {
            process
                    .destroyForcibly();
            process
                    .waitFor(
                            30,
                            TimeUnit.SECONDS
                    );
        }
    }

    // 자식 출력을 계속 읽으며 결과 줄 찾기, Spring 로그 줄은 모아만 둠
    private static String readUntilResult(
            Process process,
            List<String> output
    ) {

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process
                        .getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String text;

            while ((text = reader
                    .readLine()) != null) {
                synchronized (output) {
                    output
                            .add(text);
                }

                if (text
                        .startsWith(IngestThenWaitMain.PREFIX)) {
                    return text;
                }
            }

            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    // PG 없이 parse, chunk, PackWriter로 만든 Pack의 논리 hash, 메타는 기준 Build 값
    private String directLogicalSha(BuildResult reference) throws IOException {

        Path target = scratch
                .resolve("direct.pack");
        PackMeta meta;

        try (PackReader reader = PackReader
                .open(objectRoot
                        .resolve(reference
                                .pack()
                                .objectKey()))) {
            meta = new PackMeta(
                    "spring-boot-4.1.2",
                    "spring-boot",
                    "4.1.2",
                    reader
                            .info()
                            .buildId(),
                    metaText(
                            reader,
                            "inputSnapshotHash"
                    ),
                    reference
                            .processingConfigHash(),
                    metaText(
                            reader,
                            "parserPolicyVersion"
                    ),
                    metaText(
                            reader,
                            "chunkerPolicyVersion"
                    ),
                    metaText(
                            reader,
                            "sourceAttribution"
                    ),
                    PackRequestFixtures
                            .sources()
                            .stream()
                            .map(SourceEntry::sourceSnapshotId)
                            .sorted()
                            .toList(),
                    metaText(
                            reader,
                            "createdBy"
                    )
            );
        }

        PackWriteResult result;

        try (PackWriter writer = PackWriter
                .create(
                        target,
                        meta
                )) {
            for (SourceEntry source : PackRequestFixtures
                    .sources()) {
                writer
                        .add(document(source));
            }

            result = writer
                    .finish();
        }

        return result
                .logicalContentSha256();
    }

    // 원본 하나를 PG 없이 parse와 chunk
    private static PackDocument document(SourceEntry source) {

        return BuildFixtures
                .chunkService()
                .chunk(ParseFixtures
                        .service()
                        .parse(new SourceDocument(
                                source
                                        .documentId(),
                                "spring-boot",
                                "4.1.2",
                                source
                                        .documentType(),
                                source
                                        .canonicalUrl(),
                                source
                                        .originalUrl(),
                                source
                                        .finalUrl(),
                                source
                                        .sourceSnapshotId(),
                                source
                                        .rawSha256(),
                                PackRequestFixtures
                                        .bytes(PackRequestFixtures
                                                .fixtureOf(source))
                        )));
    }

    // 기준 Pack을 scratch로 복사, object root를 비워도 남김
    private Path copyOut(BuildResult result) throws IOException {

        Path copy = scratch
                .resolve("reference.pack");

        Files
                .copy(
                        objectRoot
                                .resolve(result
                                        .pack()
                                        .objectKey()),
                        copy
                );

        return copy;
    }

    // 원본 두 개 쓰기
    private static void writeAllRaw() {

        for (SourceEntry source : PackRequestFixtures
                .sources()) {
            PackRequestFixtures
                    .writeRaw(
                            objectRoot,
                            source
                    );
        }
    }

    // 디렉터리 아래 전부 삭제, 디렉터리 자신은 남김
    private static void deleteUnder(Path directory) throws IOException {

        if (!Files
                .exists(directory)) {
            return;
        }

        try (Stream<Path> files = Files
                .walk(directory)) {
            for (Path path : files
                    .sorted((left, right) -> right
                            .compareTo(left))
                    .toList()) {
                if (!path
                        .equals(directory)) {
                    Files
                            .delete(path);
                }
            }
        }
    }

    // Pack 하나에서 질의 결과 줄 목록
    private static List<String> rows(
            Path pack,
            String query
    ) throws Exception {

        try (PackReader reader = PackReader
                .open(pack)) {
            return reader
                    .query(connection -> {
                        List<String> lines = new ArrayList<>();

                        try (PreparedStatement statement = connection
                                .prepareStatement(query);
                                ResultSet resultSet = statement
                                        .executeQuery()) {
                            while (resultSet
                                    .next()) {
                                lines
                                        .add(resultSet
                                                .getString(1));
                            }
                        }

                        return lines;
                    });
        }
    }

    // pack_meta 문자열 값, canonical JSON 따옴표 제거
    private static String metaText(
            PackReader reader,
            String key
    ) {

        try {
            String json = reader
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
                                resultSet
                                        .next();

                                return resultSet
                                        .getString(1);
                            }
                        }
                    });

            return json
                    .substring(
                            1,
                            json
                                    .length() - 1
                    );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
