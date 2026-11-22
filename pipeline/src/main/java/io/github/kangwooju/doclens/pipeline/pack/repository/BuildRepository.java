package io.github.kangwooju.doclens.pipeline.pack.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.pack.dto.ArtifactRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.DocumentHashes;
import io.github.kangwooju.doclens.pipeline.pack.dto.DocumentHold;
import io.github.kangwooju.doclens.pipeline.pack.dto.NewBuild;
import io.github.kangwooju.doclens.pipeline.pack.dto.PackRecord;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.dto.StageDigest;
import io.github.kangwooju.doclens.pipeline.pack.dto.StoredArtifact;
import io.github.kangwooju.doclens.pipeline.pack.type.ArtifactOutcome;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;
import lombok.RequiredArgsConstructor;

/** pipeline 스키마 SQL, 충돌 무시 INSERT와 상태 CAS로 동시 호출에도 같은 상태에 수렴 */
@Repository
@RequiredArgsConstructor
public class BuildRepository {

    private static final String BUILD_COLUMNS = """
            build_id, job_key, pack_id, source_attribution, created_by, project, exact_version,
            input_snapshot_hash, processing_config_hash, parser_policy_version, chunker_policy_version,
            source_digest, document_count, status, failure_reason,
            pack_object_key, pack_checksum, pack_size_bytes, pack_logical_sha256
            """;

    // Export cursor가 한 번에 가져오는 문서 수
    private static final int EXPORT_FETCH_SIZE = 16;

    private final JdbcClient jdbcClient;

    /** RUNNING Build 한 행, 같은 작업 키가 이미 있으면 빈 값 */
    public Optional<UUID> insertBuild(NewBuild build) {

        return jdbcClient
                .sql("""
                        INSERT INTO pipeline.builds (
                            build_id, job_key, pack_id, source_attribution, created_by, project, exact_version,
                            applicability, run_input_snapshot_hash, input_scope_key, input_snapshot_hash,
                            processing_config_hash, processing_config_json, parser_policy_version,
                            chunker_policy_version, source_digest, document_count, status)
                        VALUES (
                            :buildId, :jobKey, :packId, :sourceAttribution, :createdBy, :project, :exactVersion,
                            :applicability, :runInputSnapshotHash, :inputScopeKey, :inputSnapshotHash,
                            :processingConfigHash, :processingConfigJson, :parserPolicyVersion,
                            :chunkerPolicyVersion, :sourceDigest, :documentCount, 'RUNNING')
                        ON CONFLICT (job_key) DO NOTHING
                        RETURNING build_id
                        """)
                .paramSource(build)
                .query(UUID.class)
                .optional();
    }

    /** Build의 기대 원본 전체, 열마다 배열 하나로 넘겨 바인드 변수 수 고정 */
    public void insertSources(
            UUID buildId,
            List<SourceEntry> sources
    ) {

        jdbcClient
                .sql("""
                        INSERT INTO pipeline.build_sources (
                            build_id, document_id, document_type, canonical_url, original_url, final_url,
                            source_snapshot_id, raw_sha256, raw_object_key)
                        SELECT :buildId, u.document_id, u.document_type, u.canonical_url, u.original_url,
                            u.final_url, u.source_snapshot_id, u.raw_sha256, u.raw_object_key
                        FROM unnest(
                            CAST(:documentIds AS text[]), CAST(:documentTypes AS text[]),
                            CAST(:canonicalUrls AS text[]), CAST(:originalUrls AS text[]),
                            CAST(:finalUrls AS text[]), CAST(:sourceSnapshotIds AS text[]),
                            CAST(:rawSha256s AS text[]), CAST(:rawObjectKeys AS text[]))
                            AS u(document_id, document_type, canonical_url, original_url, final_url,
                                source_snapshot_id, raw_sha256, raw_object_key)
                        """)
                .param(
                        "buildId",
                        buildId
                )
                .param(
                        "documentIds",
                        column(
                                sources,
                                SourceEntry::documentId
                        )
                )
                .param(
                        "documentTypes",
                        column(
                                sources,
                                source -> source
                                        .documentType()
                                        .name()
                        )
                )
                .param(
                        "canonicalUrls",
                        column(
                                sources,
                                SourceEntry::canonicalUrl
                        )
                )
                .param(
                        "originalUrls",
                        column(
                                sources,
                                SourceEntry::originalUrl
                        )
                )
                .param(
                        "finalUrls",
                        column(
                                sources,
                                SourceEntry::finalUrl
                        )
                )
                .param(
                        "sourceSnapshotIds",
                        column(
                                sources,
                                SourceEntry::sourceSnapshotId
                        )
                )
                .param(
                        "rawSha256s",
                        column(
                                sources,
                                SourceEntry::rawSha256
                        )
                )
                .param(
                        "rawObjectKeys",
                        column(
                                sources,
                                SourceEntry::rawObjectKey
                        )
                )
                .update();
    }

    /** 작업 키의 Build */
    public Optional<BuildRow> findByJobKey(String jobKey) {

        return jdbcClient
                .sql("SELECT " + BUILD_COLUMNS + " FROM pipeline.builds WHERE job_key = ?")
                .param(jobKey)
                .query(BuildRepository::buildRow)
                .optional();
    }

    /** buildId의 Build */
    public Optional<BuildRow> findById(UUID buildId) {

        return jdbcClient
                .sql("SELECT " + BUILD_COLUMNS + " FROM pipeline.builds WHERE build_id = ?")
                .param(buildId)
                .query(BuildRepository::buildRow)
                .optional();
    }

    /** 산출물이 아직 없는 원본, documentId 순 */
    public List<SourceEntry> pendingSources(UUID buildId) {

        return jdbcClient
                .sql("""
                        SELECT s.document_id, s.document_type, s.canonical_url, s.original_url, s.final_url,
                            s.source_snapshot_id, s.raw_sha256, s.raw_object_key
                        FROM pipeline.build_sources s
                        WHERE s.build_id = ?
                            AND NOT EXISTS (
                                SELECT 1 FROM pipeline.document_artifacts a
                                WHERE a.build_id = s.build_id AND a.document_id = s.document_id)
                        ORDER BY s.document_id
                        """)
                .param(buildId)
                .query((rs, row) -> new SourceEntry(
                        rs
                                .getString("document_id"),
                        DocumentType
                                .valueOf(rs
                                        .getString("document_type")),
                        rs
                                .getString("canonical_url"),
                        rs
                                .getString("original_url"),
                        rs
                                .getString("final_url"),
                        rs
                                .getString("source_snapshot_id"),
                        rs
                                .getString("raw_sha256"),
                        rs
                                .getString("raw_object_key")
                ))
                .list();
    }

    /** 보류된 문서와 이유, documentId 순 */
    public List<DocumentHold> holds(UUID buildId) {

        return jdbcClient
                .sql("SELECT document_id, hold_reason FROM pipeline.document_artifacts WHERE build_id = ? AND outcome = 'HELD' ORDER BY document_id")
                .param(buildId)
                .query((rs, row) -> new DocumentHold(
                        rs
                                .getString("document_id"),
                        HoldReason
                                .valueOf(rs
                                        .getString("hold_reason"))
                ))
                .list();
    }

    /** 문서 하나의 산출물, 이미 있으면 false */
    public boolean insertArtifact(
            UUID buildId,
            String documentId,
            ArtifactRow artifact
    ) {

        return jdbcClient
                .sql("""
                        INSERT INTO pipeline.document_artifacts (
                            build_id, document_id, outcome, hold_reason, structured_json, structured_sha256,
                            pack_document_json, pack_document_sha256)
                        VALUES (:buildId, :documentId, :outcome, :holdReason, :structuredJson,
                            :structuredSha256, :packDocumentJson, :packDocumentSha256)
                        ON CONFLICT (build_id, document_id) DO NOTHING
                        """)
                .param(
                        "buildId",
                        buildId
                )
                .param(
                        "documentId",
                        documentId
                )
                .param(
                        "outcome",
                        artifact
                                .outcome()
                                .name()
                )
                .param(
                        "holdReason",
                        artifact
                                .holdReason() == null ? null : artifact
                                        .holdReason()
                                        .name()
                )
                .param(
                        "structuredJson",
                        artifact
                                .structuredJson()
                )
                .param(
                        "structuredSha256",
                        artifact
                                .structuredSha256()
                )
                .param(
                        "packDocumentJson",
                        artifact
                                .packDocumentJson()
                )
                .param(
                        "packDocumentSha256",
                        artifact
                                .packDocumentSha256()
                )
                .update() == 1;
    }

    /** 저장한 산출물의 결과와 hash, JSON 본문은 읽지 않음 */
    public Optional<ArtifactRow> findArtifactHashes(
            UUID buildId,
            String documentId
    ) {

        return jdbcClient
                .sql("SELECT outcome, hold_reason, structured_sha256, pack_document_sha256 FROM pipeline.document_artifacts WHERE build_id = ? AND document_id = ?")
                .params(
                        buildId,
                        documentId
                )
                .query((rs, row) -> new ArtifactRow(
                        ArtifactOutcome
                                .valueOf(rs
                                        .getString("outcome")),
                        rs
                                .getString("hold_reason") == null ? null : HoldReason
                                        .valueOf(rs
                                                .getString("hold_reason")),
                        null,
                        rs
                                .getString("structured_sha256"),
                        null,
                        rs
                                .getString("pack_document_sha256")
                ))
                .optional();
    }

    /** 모든 원본에 산출물이 있으면 RUNNING에서 SEALED 또는 NEEDS_REVIEW로, 바뀐 상태 또는 빈 값 */
    public Optional<BuildStatus> seal(UUID buildId) {

        return jdbcClient
                .sql("""
                        UPDATE pipeline.builds b
                        SET status = CASE
                            WHEN EXISTS (
                                SELECT 1 FROM pipeline.document_artifacts a
                                WHERE a.build_id = b.build_id AND a.outcome = 'HELD')
                            THEN 'NEEDS_REVIEW' ELSE 'SEALED' END
                        WHERE b.build_id = ?
                            AND b.status = 'RUNNING'
                            AND NOT EXISTS (
                                SELECT 1 FROM pipeline.build_sources s
                                WHERE s.build_id = b.build_id
                                    AND NOT EXISTS (
                                        SELECT 1 FROM pipeline.document_artifacts a
                                        WHERE a.build_id = s.build_id AND a.document_id = s.document_id))
                        RETURNING b.status
                        """)
                .param(buildId)
                .query((rs, row) -> BuildStatus
                        .valueOf(rs
                                .getString("status")))
                .optional();
    }

    /** DONE 산출물의 hash와 JSON 바이트 수, documentId 순 */
    public List<DocumentHashes> doneHashes(UUID buildId) {

        return jdbcClient
                .sql("""
                        SELECT document_id, structured_sha256, pack_document_sha256,
                            octet_length(structured_json) AS structured_bytes,
                            octet_length(pack_document_json) AS pack_document_bytes
                        FROM pipeline.document_artifacts
                        WHERE build_id = ? AND outcome = 'DONE'
                        ORDER BY document_id
                        """)
                .param(buildId)
                .query((rs, row) -> new DocumentHashes(
                        rs
                                .getString("document_id"),
                        rs
                                .getString("structured_sha256"),
                        rs
                                .getString("pack_document_sha256"),
                        rs
                                .getLong("structured_bytes"),
                        rs
                                .getLong("pack_document_bytes")
                ))
                .list();
    }

    /** 단계 영수증 한 행 */
    public void insertReceipt(
            UUID buildId,
            String stage,
            StageDigest digest
    ) {

        jdbcClient
                .sql("INSERT INTO pipeline.verified_stage_results (build_id, stage, output_digest, item_count, byte_count) VALUES (?, ?, ?, ?, ?)")
                .params(
                        buildId,
                        stage,
                        digest
                                .digest(),
                        digest
                                .itemCount(),
                        digest
                                .byteCount()
                )
                .update();
    }

    /** RUNNING Build를 사유와 함께 FAILED로, 바뀌었으면 true */
    public boolean fail(
            UUID buildId,
            String reason
    ) {

        return jdbcClient
                .sql("UPDATE pipeline.builds SET status = 'FAILED', failure_reason = ? WHERE build_id = ? AND status = 'RUNNING'")
                .params(
                        reason,
                        buildId
                )
                .update() == 1;
    }

    /** Pack 메타의 원본 snapshot 목록, 열이 COLLATE "C"라 code point 순 */
    public List<String> sourceSnapshotIds(UUID buildId) {

        return jdbcClient
                .sql("SELECT DISTINCT source_snapshot_id FROM pipeline.build_sources WHERE build_id = ? ORDER BY source_snapshot_id")
                .param(buildId)
                .query(String.class)
                .list();
    }

    /** 단계 영수증, 없으면 빈 값 */
    public Optional<StageDigest> receipt(
            UUID buildId,
            String stage
    ) {

        return jdbcClient
                .sql("SELECT output_digest, item_count, byte_count FROM pipeline.verified_stage_results WHERE build_id = ? AND stage = ?")
                .params(
                        buildId,
                        stage
                )
                .query((rs, row) -> new StageDigest(
                        rs
                                .getString("output_digest"),
                        rs
                                .getInt("item_count"),
                        rs
                                .getLong("byte_count")
                ))
                .optional();
    }

    /** DONE 산출물을 documentId 순 cursor로 하나씩 넘김, 호출자의 트랜잭션 안에서만 흘려 읽음 */
    public void streamArtifacts(
            UUID buildId,
            Consumer<StoredArtifact> consumer
    ) {

        jdbcClient
                .sql("SELECT document_id, pack_document_sha256, pack_document_json FROM pipeline.document_artifacts WHERE build_id = ? AND outcome = 'DONE' ORDER BY document_id")
                .param(buildId)
                .withFetchSize(EXPORT_FETCH_SIZE)
                .query(rs -> {
                    consumer
                            .accept(new StoredArtifact(
                                    rs
                                            .getString("document_id"),
                                    rs
                                            .getString("pack_document_sha256"),
                                    rs
                                            .getString("pack_document_json")
                            ));
                });
    }

    /** SEALED Build를 Pack 기록과 함께 SUCCEEDED로, 바뀌었으면 true */
    public boolean markSucceeded(
            UUID buildId,
            PackRecord pack
    ) {

        return jdbcClient
                .sql("UPDATE pipeline.builds SET status = 'SUCCEEDED', pack_object_key = ?, pack_checksum = ?, pack_size_bytes = ?, pack_logical_sha256 = ? WHERE build_id = ? AND status = 'SEALED'")
                .params(
                        pack
                                .objectKey(),
                        pack
                                .checksum(),
                        pack
                                .sizeBytes(),
                        pack
                                .logicalContentSha256(),
                        buildId
                )
                .update() == 1;
    }

    // 원본 목록의 열 하나를 배열로
    private static String[] column(
            List<SourceEntry> sources,
            Function<SourceEntry, String> getter
    ) {

        return sources
                .stream()
                .map(getter)
                .toArray(String[]::new);
    }

    // builds 행 하나
    private static BuildRow buildRow(
            ResultSet rs,
            int row
    ) throws SQLException {

        String objectKey = rs
                .getString("pack_object_key");

        return new BuildRow(
                rs
                        .getObject(
                                "build_id",
                                UUID.class
                        ),
                rs
                        .getString("job_key"),
                rs
                        .getString("pack_id"),
                rs
                        .getString("source_attribution"),
                rs
                        .getString("created_by"),
                rs
                        .getString("project"),
                rs
                        .getString("exact_version"),
                rs
                        .getString("input_snapshot_hash"),
                rs
                        .getString("processing_config_hash"),
                rs
                        .getString("parser_policy_version"),
                rs
                        .getString("chunker_policy_version"),
                rs
                        .getString("source_digest"),
                rs
                        .getInt("document_count"),
                BuildStatus
                        .valueOf(rs
                                .getString("status")),
                rs
                        .getString("failure_reason"),
                objectKey == null ? null : new PackRecord(
                        objectKey,
                        rs
                                .getString("pack_checksum"),
                        rs
                                .getLong("pack_size_bytes"),
                        rs
                                .getString("pack_logical_sha256")
                )
        );
    }
}
