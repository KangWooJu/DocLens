package io.github.kangwooju.doclens.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DisplayName("pipeline 스키마 migration과 DB 제약")
class MigrationTests {

    private static final String HASH = "sha256:" + "a"
            .repeat(64);

    private static final String DOCUMENT_ID = "sha256:" + "d"
            .repeat(64);

    private static final String CONFIG_JSON = "{\"kind\":\"processing-config-v1\"}";

    @Autowired
    private JdbcClient jdbcClient;

    // 시험마다 pipeline 표 비우기
    @BeforeEach
    void clean() {

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();
    }

    @Test
    @DisplayName("Flyway 이력은 pipeline 스키마에, DB 문자 집합은 UTF8")
    void appliesMigrationInPipelineSchema() {

        assertEquals(
                1,
                jdbcClient
                        .sql("SELECT count(*) FROM pipeline.flyway_schema_history WHERE version = '001' AND success")
                        .query(Integer.class)
                        .single()
        );
        assertEquals(
                "UTF8",
                jdbcClient
                        .sql("SHOW server_encoding")
                        .query(String.class)
                        .single()
        );
    }

    @Test
    @DisplayName("Build는 RUNNING으로만 생성, pack 열은 비어 있어야 함")
    void insertsBuildOnlyAsRunning() {

        assertThrows(
                DataAccessException.class,
                () -> insertBuild(
                        UUID.randomUUID(),
                        "SEALED"
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql(buildInsert() + " RETURNING 1")
                        .param(
                                "buildId",
                                UUID.randomUUID()
                        )
                        .param(
                                "status",
                                "RUNNING"
                        )
                        .param(
                                "packKey",
                                "packs/spring-boot-4.1.2/x/a.pack"
                        )
                        .query(Integer.class)
                        .single()
        );
        assertDoesNotThrow(() -> insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        ));
    }

    @Test
    @DisplayName("Build 허용 전이는 RUNNING에서 SEALED, NEEDS_REVIEW, FAILED와 SEALED에서 SUCCEEDED뿐")
    void allowsOnlyDeclaredTransitions() {

        UUID sealed = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        assertDoesNotThrow(() -> setStatus(
                sealed,
                "SEALED"
        ));
        assertThrows(
                DataAccessException.class,
                () -> setStatus(
                        sealed,
                        "RUNNING"
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> setStatus(
                        sealed,
                        "SUCCEEDED"
                )
        );
        assertDoesNotThrow(() -> succeed(
                sealed,
                HASH
        ));
        assertThrows(
                DataAccessException.class,
                () -> setStatus(
                        sealed,
                        "SEALED"
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> succeed(
                        sealed,
                        "sha256:" + "b"
                                .repeat(64)
                )
        );

        UUID review = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        assertDoesNotThrow(() -> setStatus(
                review,
                "NEEDS_REVIEW"
        ));

        UUID failed = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        assertThrows(
                DataAccessException.class,
                () -> setStatus(
                        failed,
                        "FAILED"
                )
        );
        assertDoesNotThrow(() -> jdbcClient
                .sql("UPDATE pipeline.builds SET status = 'FAILED', failure_reason = 'raw hash mismatch' WHERE build_id = ?")
                .param(failed)
                .update());
    }

    @Test
    @DisplayName("Build 식별 열 변경과 삭제 거부")
    void keepsBuildIdentity() {

        UUID buildId = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql("UPDATE pipeline.builds SET document_count = 2 WHERE build_id = ?")
                        .param(buildId)
                        .update()
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql("DELETE FROM pipeline.builds WHERE build_id = ?")
                        .param(buildId)
                        .update()
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql(buildInsert().replace(
                                "'spring-boot-4.1.2'",
                                "'other'"
                        ) + " RETURNING 1")
                        .param(
                                "buildId",
                                UUID.randomUUID()
                        )
                        .param(
                                "status",
                                "RUNNING"
                        )
                        .param(
                                "packKey",
                                null
                        )
                        .query(Integer.class)
                        .single()
        );
    }

    @Test
    @DisplayName("원본, 산출물, 영수증 표는 추가만 허용")
    void keepsArtifactTablesAppendOnly() {

        UUID buildId = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        insertSource(
                buildId,
                "REFERENCE"
        );
        insertArtifact(
                buildId,
                "{}",
                "{\"a\":1}"
        );
        jdbcClient
                .sql("INSERT INTO pipeline.verified_stage_results (build_id, stage, output_digest, item_count, byte_count) VALUES (?, 'PARSE', ?, 1, 2)")
                .params(
                        buildId,
                        HASH
                )
                .update();

        for (String table : new String[] {"build_sources", "document_artifacts", "verified_stage_results"}) {
            assertThrows(
                    DataAccessException.class,
                    () -> jdbcClient
                            .sql("UPDATE pipeline." + table + " SET build_id = build_id WHERE build_id = ?")
                            .param(buildId)
                            .update()
            );
            assertThrows(
                    DataAccessException.class,
                    () -> jdbcClient
                            .sql("DELETE FROM pipeline." + table + " WHERE build_id = ?")
                            .param(buildId)
                            .update()
            );
        }
    }

    @Test
    @DisplayName("산출물 hash는 내용과 같아야 하고 DONE이면 비어 있으면 안 됨, HELD는 내용 없음")
    void checksArtifactShape() {

        UUID buildId = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        insertSource(
                buildId,
                "REFERENCE"
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql("INSERT INTO pipeline.document_artifacts (build_id, document_id, outcome, structured_json, structured_sha256, pack_document_json, pack_document_sha256) VALUES (?, ?, 'DONE', '{}', ?, '{}', ?)")
                        .params(
                                buildId,
                                DOCUMENT_ID,
                                HASH,
                                HASH
                        )
                        .update()
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql("INSERT INTO pipeline.document_artifacts (build_id, document_id, outcome, structured_json, pack_document_json) VALUES (?, ?, 'DONE', '{}', '{}')")
                        .params(
                                buildId,
                                DOCUMENT_ID
                        )
                        .update()
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql("INSERT INTO pipeline.document_artifacts (build_id, document_id, outcome, hold_reason) VALUES (?, ?, 'HELD', 'NOT_A_REASON')")
                        .params(
                                buildId,
                                DOCUMENT_ID
                        )
                        .update()
        );
        assertDoesNotThrow(() -> jdbcClient
                .sql("INSERT INTO pipeline.document_artifacts (build_id, document_id, outcome, hold_reason) VALUES (?, ?, 'HELD', 'TITLE_MISSING')")
                .params(
                        buildId,
                        DOCUMENT_ID
                )
                .update());
    }

    @Test
    @DisplayName("V002 뒤 보류 사유 CHECK는 같은 이름 하나, ASCIIDOC_UNRESOLVED 허용, 모르는 값 거부")
    void allowsAsciidocHoldReason() {

        UUID buildId = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        insertSource(
                buildId,
                "REFERENCE"
        );

        assertEquals(
                1,
                jdbcClient
                        .sql("SELECT count(*) FROM pipeline.flyway_schema_history WHERE version = '002' AND success")
                        .query(Integer.class)
                        .single()
        );
        assertEquals(
                List
                        .of("document_artifacts_hold_reason_check"),
                jdbcClient
                        .sql("""
                                SELECT conname FROM pg_constraint
                                WHERE conrelid = 'pipeline.document_artifacts'::regclass
                                AND pg_get_constraintdef(oid) LIKE '%hold_reason = ANY%'
                                """)
                        .query(String.class)
                        .list()
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcClient
                        .sql("INSERT INTO pipeline.document_artifacts (build_id, document_id, outcome, hold_reason) VALUES (?, ?, 'HELD', 'ASCIIDOC_UNKNOWN')")
                        .params(
                                buildId,
                                DOCUMENT_ID
                        )
                        .update()
        );
        assertDoesNotThrow(() -> jdbcClient
                .sql("INSERT INTO pipeline.document_artifacts (build_id, document_id, outcome, hold_reason) VALUES (?, ?, 'HELD', 'ASCIIDOC_UNRESOLVED')")
                .params(
                        buildId,
                        DOCUMENT_ID
                )
                .update());
    }

    @Test
    @DisplayName("문서 유형은 Pack DocumentType 이름만 허용")
    void checksDocumentType() {

        UUID buildId = insertBuild(
                UUID.randomUUID(),
                "RUNNING"
        );

        assertThrows(
                DataAccessException.class,
                () -> insertSource(
                        buildId,
                        "APPLICATION_PROPERTIES"
                )
        );
        assertDoesNotThrow(() -> insertSource(
                buildId,
                "PROPERTIES"
        ));
    }

    // 상태와 pack key만 바꿔 쓰는 Build INSERT 문
    private static String buildInsert() {

        return """
                INSERT INTO pipeline.builds (
                    build_id, job_key, pack_id, source_attribution, created_by, project, exact_version,
                    applicability, run_input_snapshot_hash, input_scope_key, input_snapshot_hash,
                    processing_config_hash, processing_config_json, parser_policy_version,
                    chunker_policy_version, source_digest, document_count, status, pack_object_key)
                VALUES (
                    :buildId, 'sha256:' || encode(sha256(convert_to(CAST(:buildId AS text), 'UTF8')), 'hex'),
                    'spring-boot-4.1.2', 'Spring documentation, Apache-2.0', 'doclens-pipeline',
                    'spring-boot', '4.1.2', 'EXACT_RELEASE',
                    'sha256:%1$s', 'sha256:%1$s', 'sha256:%1$s',
                    'sha256:' || encode(sha256(convert_to('%2$s', 'UTF8')), 'hex'), '%2$s',
                    'parser-v3', 'chunker-v2-jdk21', 'sha256:%1$s', 1, :status, :packKey)
                """
                .formatted(
                        "a"
                                .repeat(64),
                        CONFIG_JSON
                );
    }

    // 지정 상태의 Build 한 행
    private UUID insertBuild(
            UUID buildId,
            String status
    ) {

        jdbcClient
                .sql(buildInsert())
                .param(
                        "buildId",
                        buildId
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "packKey",
                        null
                )
                .update();

        return buildId;
    }

    // 문서 하나의 기대 원본
    private void insertSource(
            UUID buildId,
            String documentType
    ) {

        jdbcClient
                .sql("INSERT INTO pipeline.build_sources VALUES (?, ?, ?, 'https://docs.spring.io/a.html', 'https://docs.spring.io/a.html', 'https://docs.spring.io/a.html', 'snap-1', ?, 'raw/spring-boot/4.1.2/a.html')")
                .params(
                        buildId,
                        DOCUMENT_ID,
                        documentType,
                        HASH
                )
                .update();
    }

    // DB가 hash를 계산한 DONE 산출물
    private void insertArtifact(
            UUID buildId,
            String structuredJson,
            String packDocumentJson
    ) {

        jdbcClient
                .sql("""
                        INSERT INTO pipeline.document_artifacts (
                            build_id, document_id, outcome, structured_json, structured_sha256,
                            pack_document_json, pack_document_sha256)
                        VALUES (:buildId, :documentId, 'DONE', :structured,
                            'sha256:' || encode(sha256(convert_to(:structured, 'UTF8')), 'hex'),
                            :packDocument,
                            'sha256:' || encode(sha256(convert_to(:packDocument, 'UTF8')), 'hex'))
                        """)
                .param(
                        "buildId",
                        buildId
                )
                .param(
                        "documentId",
                        DOCUMENT_ID
                )
                .param(
                        "structured",
                        structuredJson
                )
                .param(
                        "packDocument",
                        packDocumentJson
                )
                .update();
    }

    // 상태만 바꾸는 UPDATE
    private void setStatus(
            UUID buildId,
            String status
    ) {

        jdbcClient
                .sql("UPDATE pipeline.builds SET status = ? WHERE build_id = ?")
                .params(
                        status,
                        buildId
                )
                .update();
    }

    // pack 열 4개를 채우는 SUCCEEDED 전이
    private void succeed(
            UUID buildId,
            String checksum
    ) {

        jdbcClient
                .sql("UPDATE pipeline.builds SET status = 'SUCCEEDED', pack_object_key = 'packs/spring-boot-4.1.2/b/a.pack', pack_checksum = ?, pack_size_bytes = 10, pack_logical_sha256 = ? WHERE build_id = ?")
                .params(
                        checksum,
                        checksum,
                        buildId
                )
                .update();
    }
}
