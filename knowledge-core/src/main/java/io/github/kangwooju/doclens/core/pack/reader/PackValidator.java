package io.github.kangwooju.doclens.core.pack.reader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.kangwooju.doclens.core.pack.dto.PackInfo;
import io.github.kangwooju.doclens.core.pack.exception.PackValidationException;
import io.github.kangwooju.doclens.core.pack.support.LogicalContentHash;
import io.github.kangwooju.doclens.core.pack.type.PackCapability;
import io.github.kangwooju.doclens.core.pack.type.PackMetaKey;
import io.github.kangwooju.doclens.core.pack.type.PackValidationReason;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pack 파일 전체 검증과 PackInfo 생성 */
final class PackValidator {

    private static final long APPLICATION_ID = 1397771083L;
    private static final Pattern FIRST_TOKEN = Pattern
            .compile("[\\p{L}\\p{N}]+");
    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    // 정적 메서드 전용
    private PackValidator() {
    }

    // 전체 검증 순서 실행과 PackInfo 생성
    static PackInfo validate(Connection connection) throws SQLException {

        try (Statement statement = connection
                .createStatement()) {
            requireHeader(statement);
            Map<String, JsonNode> meta = readMeta(statement);
            requireVersions(meta);
            requireIntegrity(statement);
            requireConsistentContent(
                    connection,
                    statement,
                    text(
                            meta,
                            PackMetaKey.DOC_VERSION
                    )
            );
            requireSearchIndex(connection);
            requireLogicalHash(
                    connection,
                    text(
                            meta,
                            PackMetaKey.LOGICAL_CONTENT_SHA256
                    )
            );

            return new PackInfo(
                    text(
                            meta,
                            PackMetaKey.PACK_ID
                    ),
                    text(
                            meta,
                            PackMetaKey.PROJECT
                    ),
                    text(
                            meta,
                            PackMetaKey.DOC_VERSION
                    ),
                    text(
                            meta,
                            PackMetaKey.BUILD_ID
                    ),
                    json(
                            meta,
                            PackMetaKey.PACK_SCHEMA_VERSION
                    )
                            .asInt(),
                    json(
                            meta,
                            PackMetaKey.MIN_READER_VERSION
                    )
                            .asInt(),
                    text(
                            meta,
                            PackMetaKey.LOGICAL_CONTENT_SHA256
                    ),
                    json(
                            meta,
                            PackMetaKey.CAPABILITIES
                    )
                            .valueStream()
                            .map(JsonNode::asString)
                            .toList(),
                    count(
                            statement,
                            "SELECT count(*) FROM documents"
                    ),
                    count(
                            statement,
                            "SELECT count(*) FROM chunks"
                    )
            );
        }
    }

    // application_id, user_version 확인
    private static void requireHeader(Statement statement) throws SQLException {

        long applicationId = count(
                statement,
                "PRAGMA application_id"
        );

        if (applicationId != APPLICATION_ID) {
            throw new PackValidationException(
                    PackValidationReason.NOT_A_PACK,
                    "application_id is " + applicationId + ", expected " + APPLICATION_ID
            );
        }

        long userVersion = count(
                statement,
                "PRAGMA user_version"
        );

        if (userVersion != PackReader.READER_VERSION) {
            throw new PackValidationException(
                    PackValidationReason.UNSUPPORTED_SCHEMA_VERSION,
                    "user_version " + userVersion + " is not supported by reader " + PackReader.READER_VERSION
            );
        }
    }

    // pack_meta JSON 값 읽기와 필수 키 확인
    private static Map<String, JsonNode> readMeta(Statement statement) throws SQLException {

        Map<String, JsonNode> meta = new HashMap<>();

        try (ResultSet rows = statement
                .executeQuery("SELECT key, value FROM pack_meta")) {
            while (rows
                    .next()) {
                try {
                    meta
                            .put(
                                    rows
                                            .getString(1),
                                    JSON_MAPPER
                                            .readTree(rows
                                                    .getString(2))
                            );
                } catch (JacksonException exception) {
                    throw new PackValidationException(
                            PackValidationReason.INVALID_META,
                            "pack_meta " + rows
                                    .getString(1) + " is not JSON",
                            exception
                    );
                }
            }
        }

        for (PackMetaKey key : PackMetaKey
                .values()) {
            if (!meta
                    .containsKey(key
                            .key())) {
                throw new PackValidationException(
                        PackValidationReason.INVALID_META,
                        "pack_meta is missing " + key
                                .key()
                );
            }
        }

        if (!"DOCUMENT"
                .equals(text(
                        meta,
                        PackMetaKey.PACK_TYPE
                ))) {
            throw new PackValidationException(
                    PackValidationReason.INVALID_META,
                    "packType " + text(
                            meta,
                            PackMetaKey.PACK_TYPE
                    ) + " is not supported"
            );
        }

        JsonNode embedding = json(
                meta,
                PackMetaKey.EMBEDDING
        );

        if (!embedding
                .isNull()) {
            throw new PackValidationException(
                    PackValidationReason.INVALID_META,
                    "embedding must be null for reader " + PackReader.READER_VERSION
            );
        }

        JsonNode capabilities = json(
                meta,
                PackMetaKey.CAPABILITIES
        );

        for (JsonNode capability : capabilities
                .values()) {
            if (!isKnownCapability(capability
                    .asString())) {
                throw new PackValidationException(
                        PackValidationReason.INVALID_META,
                        "unknown capability " + capability
                                .asString()
                );
            }
        }

        return meta;
    }

    // packSchemaVersion, minReaderVersion 지원 범위 확인
    private static void requireVersions(Map<String, JsonNode> meta) {

        JsonNode schemaVersion = json(
                meta,
                PackMetaKey.PACK_SCHEMA_VERSION
        );

        if (!schemaVersion
                .isInt()
                || schemaVersion
                        .asInt() != PackReader.READER_VERSION) {
            throw new PackValidationException(
                    PackValidationReason.UNSUPPORTED_SCHEMA_VERSION,
                    "packSchemaVersion " + schemaVersion + " is not supported by reader " + PackReader.READER_VERSION
            );
        }

        JsonNode minReaderVersion = json(
                meta,
                PackMetaKey.MIN_READER_VERSION
        );

        if (!minReaderVersion
                .isInt()
                || minReaderVersion
                        .asInt() > PackReader.READER_VERSION) {
            throw new PackValidationException(
                    PackValidationReason.UNSUPPORTED_READER_VERSION,
                    "pack requires reader " + minReaderVersion + ", this reader is " + PackReader.READER_VERSION
            );
        }
    }

    // integrity_check, foreign_key_check 확인
    private static void requireIntegrity(Statement statement) throws SQLException {

        try (ResultSet rows = statement
                .executeQuery("PRAGMA integrity_check")) {
            rows
                    .next();

            if (!"ok"
                    .equals(rows
                            .getString(1))) {
                throw new PackValidationException(
                        PackValidationReason.CORRUPT,
                        "integrity_check: " + rows
                                .getString(1)
                );
            }
        }

        try (ResultSet rows = statement
                .executeQuery("PRAGMA foreign_key_check")) {
            if (rows
                    .next()) {
                throw new PackValidationException(
                        PackValidationReason.CORRUPT,
                        "foreign_key_check failed in table " + rows
                                .getString(1)
                );
            }
        }
    }

    // 청크와 섹션의 문서 일치, 부모 순환, 기본 변형, 버전, 청크 rowid 순서 확인
    private static void requireConsistentContent(
            Connection connection,
            Statement statement,
            String docVersion
    ) throws SQLException {

        requireZero(
                statement,
                "SELECT count(*) FROM chunks c JOIN sections s ON s.section_id = c.section_id "
                        + "WHERE s.document_id <> c.document_id",
                "chunk and its section belong to different documents"
        );
        requireZero(
                statement,
                "SELECT count(*) FROM sections child JOIN sections parent "
                        + "ON parent.section_id = child.parent_section_id "
                        + "WHERE parent.document_id <> child.document_id",
                "parent section belongs to another document"
        );
        requireZero(
                statement,
                "WITH RECURSIVE walk(start, current, depth) AS ("
                        + " SELECT section_id, parent_section_id, 1 FROM sections WHERE parent_section_id IS NOT NULL"
                        + " UNION ALL"
                        + " SELECT walk.start, sections.parent_section_id, walk.depth + 1"
                        + " FROM walk JOIN sections ON sections.section_id = walk.current"
                        + " WHERE sections.parent_section_id IS NOT NULL"
                        + " AND walk.depth <= (SELECT count(*) FROM sections))"
                        + " SELECT count(*) FROM walk WHERE current = start",
                "section parent chain has a cycle"
        );
        requireZero(
                statement,
                "SELECT count(*) FROM (SELECT sum(is_default) AS defaults FROM code_variants "
                        + "GROUP BY chunk_rowid, block_index) WHERE defaults <> 1",
                "code block without exactly one default variant"
        );

        try (PreparedStatement versions = connection
                .prepareStatement("SELECT count(*) FROM documents WHERE applicability <> 'EXACT_RELEASE' OR exact_version <> ?")) {
            versions
                    .setString(
                            1,
                            docVersion
                    );

            try (ResultSet rows = versions
                    .executeQuery()) {
                rows
                        .next();

                if (rows
                        .getLong(1) != 0) {
                    throw new PackValidationException(
                            PackValidationReason.INCONSISTENT_CONTENT,
                            "documents outside exact release " + docVersion
                    );
                }
            }
        }

        requireRowidOrder(statement);
    }

    // 청크 rowid가 1..N 순서인지 확인
    private static void requireRowidOrder(Statement statement) throws SQLException {

        try (ResultSet rows = statement
                .executeQuery(
                        "SELECT c.rowid FROM chunks c JOIN sections s ON s.section_id = c.section_id "
                                + "ORDER BY c.document_id COLLATE BINARY, s.ordinal, c.ordinal, c.chunk_id COLLATE BINARY"
                )) {
            long expected = 1;

            while (rows
                    .next()) {
                if (rows
                        .getLong(1) != expected) {
                    throw new PackValidationException(
                            PackValidationReason.INCONSISTENT_CONTENT,
                            "chunk rowid " + rows
                                    .getLong(1) + " found where " + expected + " was expected"
                    );
                }

                expected++;
            }
        }
    }

    // 표본 MATCH 조회로 검색 색인 확인
    private static void requireSearchIndex(Connection connection) throws SQLException {

        // 앞쪽 청크의 토큰 하나로 실제 MATCH 검색 시 그 청크 조회 여부 확인
        try (PreparedStatement sample = connection
                .prepareStatement("SELECT rowid, content FROM chunks ORDER BY rowid LIMIT 20");
             PreparedStatement match = connection
                     .prepareStatement("SELECT count(*) FROM chunks_fts WHERE chunks_fts MATCH ? AND rowid = ?");
             ResultSet rows = sample
                     .executeQuery()) {

            while (rows
                    .next()) {
                Matcher token = FIRST_TOKEN
                        .matcher(rows
                                .getString(2));

                if (!token
                        .find()) {
                    continue;
                }

                match
                        .setString(
                                1,
                                "\"" + token
                                        .group() + "\""
                        );
                match
                        .setLong(
                                2,
                                rows
                                        .getLong(1)
                        );

                try (ResultSet found = match
                        .executeQuery()) {
                    found
                            .next();

                    if (found
                            .getLong(1) != 1) {
                        throw new PackValidationException(
                                PackValidationReason.SEARCH_INDEX_BROKEN,
                                "chunk " + rows
                                        .getLong(1) + " is not found by its own token"
                        );
                    }
                }

                return;
            }
        }
    }

    // 다시 계산한 논리 해시와 기록 값 일치 확인
    private static void requireLogicalHash(
            Connection connection,
            String recorded
    ) throws SQLException {

        String actual = LogicalContentHash
                .compute(connection);

        if (!actual
                .equals(recorded)) {
            throw new PackValidationException(
                    PackValidationReason.LOGICAL_HASH_MISMATCH,
                    "content hash " + actual + " does not match recorded " + recorded
            );
        }
    }

    // 위반 행 개수 0 확인
    private static void requireZero(
            Statement statement,
            String sql,
            String problem
    ) throws SQLException {

        long found = count(
                statement,
                sql
        );

        if (found != 0) {
            throw new PackValidationException(
                    PackValidationReason.INCONSISTENT_CONTENT,
                    problem + " (" + found + ")"
            );
        }
    }

    // 단일 개수 조회
    private static long count(
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

    // 메타 값의 JSON 문자열 추출
    private static String text(
            Map<String, JsonNode> meta,
            PackMetaKey key
    ) {

        JsonNode value = json(
                meta,
                key
        );

        if (value == null || !value
                .isString()) {
            throw new PackValidationException(
                    PackValidationReason.INVALID_META,
                    "pack_meta " + key
                            .key() + " must be a JSON string"
            );
        }

        return value
                .asString();
    }

    // 메타 키 하나의 JSON 값, 없으면 null
    private static JsonNode json(
            Map<String, JsonNode> meta,
            PackMetaKey key
    ) {

        return meta
                .get(key
                        .key());
    }

    // 알려진 Pack 기능 이름 여부
    private static boolean isKnownCapability(String name) {

        return Stream
                .of(PackCapability
                        .values())
                .anyMatch(capability -> capability
                        .name()
                        .equals(name));
    }
}
