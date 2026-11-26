package io.github.kangwooju.doclens.pipeline.pack.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import io.github.kangwooju.doclens.core.pack.reader.PackReader;
import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunIncompleteException;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.policy.ProcessingConfigPolicy;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures.Bundle;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures.Document;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;
import io.github.kangwooju.doclens.testsupport.S3MockTestConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@SpringBootTest(properties = "doclens.pipeline.object-store=s3")
@Import({
        PostgresTestConfiguration.class,
        S3MockTestConfiguration.class
})
@DisplayName("S3에 올린 collector readyRun 묶음으로 Build와 Pack 업로드")
class CollectedBuildTests {

    @TempDir
    static Path objectRoot;

    @Autowired
    private PackBuildFacade facade;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private S3Client s3Client;

    // 시험 Pack 작업 디렉터리 지정
    @DynamicPropertySource
    static void objectRoot(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.pipeline.object-root",
                        () -> objectRoot
                                .toString()
                );
    }

    // 시험마다 Build 표 비움
    @BeforeEach
    void truncate() {

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();
    }

    @Test
    @DisplayName("합성 fixture readyRun을 buildCollected하면 SUCCEEDED, Pack이 S3 packs 경로에 같은 checksum으로 있음")
    void buildsCollectedRun() {

        List<BuildResult> results = facade
                .buildCollected(upload(CollectedRunFixtures
                        .bundle()));

        assertEquals(
                1,
                results
                        .size()
        );

        BuildResult result = results
                .getFirst();

        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertTrue(result
                .pack()
                .objectKey()
                .matches("packs/spring-boot-4\\.1\\.2/" + result
                        .buildId() + "/[0-9a-f]{64}\\.pack"));
        assertEquals(
                result
                        .pack()
                        .checksum(),
                CollectedRunFixtures
                        .sha256(s3Client
                                .getObjectAsBytes(GetObjectRequest
                                        .builder()
                                        .bucket(S3MockTestConfiguration.BUCKET)
                                        .key(result
                                                .pack()
                                                .objectKey())
                                        .build())
                                .asByteArray())
        );
    }

    @Test
    @DisplayName("합성 git blob readyRun은 예시를 include로 읽어 AsciiDoc 두 쪽 Build SUCCEEDED, Pack에 인용과 include, xref 제목 준 쪽 기록")
    void buildsCollectedGitRun() throws Exception {

        List<BuildResult> results = facade
                .buildCollected(upload(CollectedRunFixtures
                        .write(CollectedRunFixtures
                                .gitParts(CollectedRunFixtures
                                        .succeededGitDocuments()))));
        BuildResult result = results
                .getFirst();

        assertEquals(
                1,
                results
                        .size()
        );
        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertEquals(
                2,
                jdbcClient
                        .sql("SELECT count(*) FROM pipeline.build_sources WHERE build_id = ?")
                        .param(result
                                .buildId())
                        .query(Integer.class)
                        .single()
        );

        Path pack = objectRoot
                .resolve("git.pack");
        Files
                .write(
                        pack,
                        s3Client
                                .getObjectAsBytes(GetObjectRequest
                                        .builder()
                                        .bucket(S3MockTestConfiguration.BUCKET)
                                        .key(result
                                                .pack()
                                                .objectKey())
                                        .build())
                                .asByteArray()
                );

        try (PackReader reader = PackReader
                .open(pack)) {
            assertEquals(
                    List
                            .of(
                                    ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "nested/deep.adoc 0",
                                    ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "sample.adoc 5"
                            ),
                    reader
                            .query(connection -> texts(
                                    connection,
                                    "SELECT url || ' ' || coalesce(json_array_length(source_map_json, '$.includes'), 0) FROM documents ORDER BY url"
                            ))
            );
            assertEquals(
                    List
                            .of(
                                    ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "nested/deep.adoc " + ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "sample.adoc",
                                    ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "sample.adoc -"
                            ),
                    reader
                            .query(connection -> texts(
                                    connection,
                                    "SELECT url || ' ' || coalesce(json_extract(source_map_json, '$.titleSources[0].url'), '-') FROM documents ORDER BY url"
                            ))
            );
            assertEquals(
                    List
                            .of("https://docs.spring.io/spring-boot/4.1/reference/nested/deep.html#deep.intro"),
                    reader
                            .query(connection -> texts(
                                    connection,
                                    "SELECT DISTINCT json_extract(m.value, '$.citation.readerUrl') FROM chunks c"
                                            + " JOIN sections s ON s.section_id = c.section_id, json_each(c.source_map_json) m"
                                            + " WHERE s.anchor = 'deep.intro'"
                            ))
            );
            assertEquals(
                    List
                            .of(ProcessingConfigPolicy.SOURCE_ATTRIBUTION),
                    reader
                            .query(connection -> texts(
                                    connection,
                                    "SELECT json_extract(value, '$') FROM pack_meta WHERE key = 'sourceAttribution'"
                            ))
            );
        }
    }

    @Test
    @DisplayName("필수 문서 실패가 있으면 Build 행 없이 입력 보류")
    void incompleteRunWritesNoBuild() {

        ObjectRef readyRun = upload(CollectedRunFixtures
                .write(CollectedRunFixtures
                        .parts(List
                                .of(
                                        new Document(
                                                "reference.html",
                                                "REFERENCE",
                                                true,
                                                true
                                        ),
                                        new Document(
                                                "f16-sections-tabs-table-links.html",
                                                "REFERENCE",
                                                true,
                                                false
                                        )
                                ))));

        assertThrows(
                CollectedRunIncompleteException.class,
                () -> facade
                        .buildCollected(readyRun)
        );
        assertEquals(
                0,
                jdbcClient
                        .sql("SELECT count(*) FROM pipeline.builds")
                        .query(Integer.class)
                        .single()
        );
    }

    // 조회 결과 첫 열의 글 목록
    private static List<String> texts(
            Connection connection,
            String sql
    ) throws SQLException {

        List<String> texts = new ArrayList<>();

        try (PreparedStatement statement = connection
                .prepareStatement(sql); ResultSet rows = statement
                        .executeQuery()) {
            while (rows
                    .next()) {
                texts
                        .add(rows
                                .getString(1));
            }
        }

        return texts;
    }

    // 묶음의 객체를 모두 S3에 올리고 readyRun objectRef 반환
    private ObjectRef upload(Bundle bundle) {

        for (Map.Entry<String, byte[]> object : bundle
                .objects()
                .entrySet()) {
            s3Client
                    .putObject(
                            PutObjectRequest
                                    .builder()
                                    .bucket(S3MockTestConfiguration.BUCKET)
                                    .key(object
                                            .getKey())
                                    .build(),
                            RequestBody
                                    .fromBytes(object
                                            .getValue())
                    );
        }

        return bundle
                .readyRun();
    }
}
