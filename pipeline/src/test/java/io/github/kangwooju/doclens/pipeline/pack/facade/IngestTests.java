package io.github.kangwooju.doclens.pipeline.pack.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.pack.BuildFixtures;
import io.github.kangwooju.doclens.pipeline.pack.dto.ArtifactRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildInputConflictException;
import io.github.kangwooju.doclens.pipeline.pack.exception.ProcessingConfigMismatchException;
import io.github.kangwooju.doclens.pipeline.pack.exception.StageNondeterminismException;
import io.github.kangwooju.doclens.pipeline.pack.service.BuildService;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("원본에서 봉인까지 ingest")
class IngestTests {

    private static final String BASE_URL = "https://docs.spring.io/spring-boot/4.1.2/reference/features/";

    @TempDir
    static Path objectRoot;

    @Autowired
    private PackBuildFacade facade;

    @Autowired
    private BuildService buildService;

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

    // 시험마다 pipeline 표와 원본 파일 비우기
    @BeforeEach
    void clean() throws IOException {

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
    }

    @Test
    @DisplayName("첫 ingest는 문서마다 산출물을 저장하고 봉인, PARSE와 CHUNK 영수증")
    void sealsAfterAllArtifacts() throws IOException {

        BuildResult result = facade
                .ingest(request(List
                        .of(
                                source(
                                        "reference.html",
                                        "reference.html"
                                ),
                                source(
                                        "f16-sections-tabs-table-links.html",
                                        "f16.html"
                                )
                        )));

        assertEquals(
                BuildStatus.SEALED,
                result
                        .status()
        );
        assertEquals(
                2,
                count("SELECT count(*) FROM pipeline.document_artifacts WHERE outcome = 'DONE'")
        );
        assertEquals(
                List
                        .of(
                                "CHUNK",
                                "PARSE"
                        ),
                jdbcClient
                        .sql("SELECT stage FROM pipeline.verified_stage_results ORDER BY stage")
                        .query(String.class)
                        .list()
        );
    }

    @Test
    @DisplayName("같은 요청 재전송은 같은 Build, 행 수 그대로")
    void resendsIdempotently() throws IOException {

        BuildRequest request = request(List
                .of(source(
                        "reference.html",
                        "reference.html"
                )));
        BuildResult first = facade
                .ingest(request);
        BuildResult second = facade
                .ingest(request);

        assertEquals(
                first
                        .buildId(),
                second
                        .buildId()
        );
        assertEquals(
                1,
                count("SELECT count(*) FROM pipeline.builds")
        );
        assertEquals(
                1,
                count("SELECT count(*) FROM pipeline.document_artifacts")
        );
        assertEquals(
                2,
                count("SELECT count(*) FROM pipeline.verified_stage_results")
        );
    }

    @Test
    @DisplayName("같은 작업 키에 다른 원본이면 충돌")
    void rejectsDifferentSourcesForSameJob() throws IOException {

        SourceEntry original = source(
                "reference.html",
                "reference.html"
        );

        facade
                .ingest(request(List
                        .of(original)));

        SourceEntry changed = new SourceEntry(
                original
                        .documentId(),
                original
                        .documentType(),
                original
                        .canonicalUrl(),
                original
                        .originalUrl(),
                original
                        .finalUrl(),
                "snap-other",
                original
                        .rawSha256(),
                original
                        .rawObjectKey()
        );

        assertThrows(
                BuildInputConflictException.class,
                () -> facade
                        .ingest(request(List
                                .of(changed)))
        );
    }

    @Test
    @DisplayName("요청 처리 설정 hash가 다르면 Build 행을 쓰기 전에 거부")
    void rejectsConfigMismatchBeforeWriting() throws IOException {

        BuildRequest valid = request(List
                .of(source(
                        "reference.html",
                        "reference.html"
                )));

        assertThrows(
                ProcessingConfigMismatchException.class,
                () -> facade
                        .ingest(new BuildRequest(
                                valid
                                        .project(),
                                valid
                                        .exactVersion(),
                                valid
                                        .applicability(),
                                valid
                                        .inputScopeKey(),
                                valid
                                        .runInputSnapshotHash(),
                                hash('9'),
                                null,
                                valid
                                        .sources()
                        ))
        );
        assertEquals(
                0,
                count("SELECT count(*) FROM pipeline.builds")
        );
    }

    @Test
    @DisplayName("프로필이 맞지 않는 문서는 보류, Build는 NEEDS_REVIEW, 재전송 결과에도 보류 목록")
    void holdsUnmatchedDocument() throws IOException {

        BuildRequest request = request(List
                .of(
                        source(
                                "reference.html",
                                "reference.html"
                        ),
                        sourceAt(
                                "f16-sections-tabs-table-links.html",
                                "https://docs.spring.io/spring-boot/4.1.2/how-to/f16.html"
                        )
                ));
        BuildResult first = facade
                .ingest(request);
        BuildResult resent = facade
                .ingest(request);

        assertEquals(
                BuildStatus.NEEDS_REVIEW,
                first
                        .status()
        );
        assertEquals(
                HoldReason.SELECTOR_UNVERIFIED,
                resent
                        .holds()
                        .getFirst()
                        .reason()
        );
        assertEquals(
                0,
                count("SELECT count(*) FROM pipeline.verified_stage_results")
        );
    }

    @Test
    @DisplayName("원본 파일이 없으면 있는 문서만 저장하고 RUNNING, 파일이 생긴 뒤 재전송하면 봉인")
    void resumesAfterMissingSource() throws IOException {

        SourceEntry present = source(
                "reference.html",
                "reference.html"
        );
        SourceEntry later = source(
                "f16-sections-tabs-table-links.html",
                "f16.html"
        );
        Path laterFile = objectRoot
                .resolve(later
                        .rawObjectKey());
        byte[] laterBytes = Files
                .readAllBytes(laterFile);
        BuildRequest request = request(List
                .of(
                        present,
                        later
                ));

        Files
                .delete(laterFile);

        assertEquals(
                BuildStatus.RUNNING,
                facade
                        .ingest(request)
                        .status()
        );
        assertEquals(
                1,
                count("SELECT count(*) FROM pipeline.document_artifacts")
        );

        Files
                .write(
                        laterFile,
                        laterBytes
                );

        assertEquals(
                BuildStatus.SEALED,
                facade
                        .ingest(request)
                        .status()
        );
    }

    @Test
    @DisplayName("원본 bytes가 rawSha256과 다르면 FAILED, 재전송도 FAILED")
    void failsOnRawHashMismatch() throws IOException {

        SourceEntry source = source(
                "reference.html",
                "reference.html"
        );
        BuildRequest request = request(List
                .of(source));

        Files
                .writeString(
                        objectRoot
                                .resolve(source
                                        .rawObjectKey()),
                        "<html>changed</html>"
                );

        BuildResult failed = facade
                .ingest(request);

        assertEquals(
                BuildStatus.FAILED,
                failed
                        .status()
        );
        assertNotNull(failed
                .failureReason());
        assertEquals(
                BuildStatus.FAILED,
                facade
                        .ingest(request)
                        .status()
        );
    }

    @Test
    @DisplayName("저장한 산출물과 다른 산출물이 오면 비결정성 예외, 처음 행은 그대로")
    void detectsNondeterminism() throws IOException {

        SourceEntry source = source(
                "reference.html",
                "reference.html"
        );
        UUID buildId = facade
                .ingest(request(List
                        .of(source)))
                .buildId();
        String stored = storedPackSha(
                buildId,
                source
                        .documentId()
        );
        ArtifactRow different = buildService
                .prepareArtifact(
                        BuildFixtures
                                .structured("f16-sections-tabs-table-links.html"),
                        BuildFixtures
                                .packDocument("f16-sections-tabs-table-links.html")
                );

        assertThrows(
                StageNondeterminismException.class,
                () -> buildService
                        .saveArtifact(
                                buildId,
                                source
                                        .documentId(),
                                different
                        )
        );
        assertEquals(
                stored,
                storedPackSha(
                        buildId,
                        source
                                .documentId()
                )
        );
    }

    @Test
    @DisplayName("문서 100,001개 요청은 입력 오류, Build 행 없음")
    void rejectsTooManyDocuments() {

        List<SourceEntry> sources = new ArrayList<>();

        for (int index = 0; index <= 100_000; index++) {
            sources
                    .add(new SourceEntry(
                            CanonicalJson
                                    .sha256(Integer
                                            .toString(index)),
                            DocumentType.REFERENCE,
                            BASE_URL + index + ".html",
                            BASE_URL + index + ".html",
                            BASE_URL + index + ".html",
                            "snap-1",
                            hash('a'),
                            "raw/spring-boot/4.1.2/" + index + ".html"
                    ));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> facade
                        .ingest(request(sources))
        );
        assertEquals(
                0,
                count("SELECT count(*) FROM pipeline.builds")
        );
    }

    @Test
    @DisplayName("원본 1,000개는 한 문장으로 저장, 파일이 없으면 RUNNING")
    void insertsManySourcesAtOnce() {

        List<SourceEntry> sources = new ArrayList<>();

        for (int index = 0; index < 1_000; index++) {
            sources
                    .add(new SourceEntry(
                            CanonicalJson
                                    .sha256(Integer
                                            .toString(index)),
                            DocumentType.REFERENCE,
                            BASE_URL + index + ".html",
                            BASE_URL + index + ".html",
                            BASE_URL + index + ".html",
                            "snap-1",
                            hash('a'),
                            "raw/spring-boot/4.1.2/" + index + ".html"
                    ));
        }

        assertEquals(
                BuildStatus.RUNNING,
                facade
                        .ingest(request(sources))
                        .status()
        );
        assertEquals(
                1_000,
                count("SELECT count(*) FROM pipeline.build_sources")
        );
    }

    // 고정 범위 값과 현재 처리 설정 hash의 요청
    private static BuildRequest request(List<SourceEntry> sources) {

        return new BuildRequest(
                "spring-boot",
                "4.1.2",
                "EXACT_RELEASE",
                hash('1'),
                hash('2'),
                BuildFixtures
                        .currentHash(),
                null,
                sources
        );
    }

    // fixture를 raw 경로에 쓰고 Reference URL로 만든 원본
    private static SourceEntry source(
            String fixture,
            String page
    ) throws IOException {

        return sourceAt(
                fixture,
                BASE_URL + page
        );
    }

    // fixture를 raw 경로에 쓰고 지정 URL로 만든 원본
    private static SourceEntry sourceAt(
            String fixture,
            String url
    ) throws IOException {

        byte[] bytes = ParseFixtures
                .html(fixture)
                .getBytes(StandardCharsets.UTF_8);
        String key = "raw/spring-boot/4.1.2/" + fixture;
        Path file = objectRoot
                .resolve(key);

        Files
                .createDirectories(file
                        .getParent());
        Files
                .write(
                        file,
                        bytes
                );

        return new SourceEntry(
                CanonicalJson
                        .sha256(url),
                DocumentType.REFERENCE,
                url,
                url,
                url,
                "snap-1",
                sha256(bytes),
                key
        );
    }

    // 저장한 CHUNK 산출물 hash
    private String storedPackSha(
            UUID buildId,
            String documentId
    ) {

        return jdbcClient
                .sql("SELECT pack_document_sha256 FROM pipeline.document_artifacts WHERE build_id = ? AND document_id = ?")
                .params(
                        buildId,
                        documentId
                )
                .query(String.class)
                .single();
    }

    // bytes의 sha256: 접두 hex
    private static String sha256(byte[] bytes) {

        try {
            return "sha256:" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // sha256: 접두와 같은 문자 64개
    private static String hash(char value) {

        return "sha256:" + String
                .valueOf(value)
                .repeat(64);
    }

    // 정수 하나를 돌려주는 SQL 결과
    private int count(String sql) {

        return jdbcClient
                .sql(sql)
                .query(Integer.class)
                .single();
    }
}
