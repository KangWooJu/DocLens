package io.github.kangwooju.doclens.core.pack;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import io.github.kangwooju.doclens.core.pack.PackValidationException.Reason;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검증 후 읽기 전용으로 여는 Pack Reader(Reader 버전 1)
 *
 * <p>읽기 전용 열기, query_only, 확장 로드 금지, busy_timeout 1000ms, 파일 변경 없는 열기
 * 검색 API는 mcp-server 단계(F12)에서 추가
 */
public final class PackReader implements AutoCloseable {

    /** 이 Reader가 이해하는 Pack schema와 Reader 버전 */
    public static final int READER_VERSION = 1;

    private static final long APPLICATION_ID = 1397771083L;
    private static final int BUSY_TIMEOUT_MILLIS = 1000;
    private static final Set<String> REQUIRED_META_KEYS = Set
            .of(
                    "packId",
                    "packType",
                    "project",
                    "docVersion",
                    "buildId",
                    "packSchemaVersion",
                    "minReaderVersion",
                    "inputSnapshotHash",
                    "processingConfigHash",
                    "parserPolicyVersion",
                    "chunkerPolicyVersion",
                    "sourceAttribution",
                    "sourceSnapshotIds",
                    "logicalContentSha256",
                    "capabilities",
                    "embedding",
                    "createdBy"
            );
    private static final Set<String> KNOWN_CAPABILITIES = Set
            .of(
                    "BM25",
                    "SECTIONS",
                    "VARIANTS"
            );
    private static final Pattern FIRST_TOKEN = Pattern
            .compile("[\\p{L}\\p{N}]+");
    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    private final Connection connection;
    private final PackInfo info;

    private PackReader(
            Connection connection,
            PackInfo info
    ) {

        this.connection = connection;
        this.info = info;
    }

    /** Pack 파일 읽기 전용 열기와 전체 검증, 실패 시 이유와 함께 거절 */
    public static PackReader open(Path packFile) {

        if (!Files
                .isRegularFile(
                        packFile,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            throw new PackValidationException(
                    Reason.NOT_A_FILE,
                    "not a regular file (symbolic links are refused): " + packFile
            );
        }

        Connection connection = null;

        try {
            connection = readOnlyConnection(packFile);
            PackInfo info = validate(connection);

            return new PackReader(
                    connection,
                    info
            );
        } catch (SQLException exception) {
            closeQuietly(connection);

            throw new PackValidationException(
                    Reason.CORRUPT,
                    "cannot read pack " + packFile + ": " + exception
                            .getMessage(),
                    exception
            );
        } catch (RuntimeException exception) {
            closeQuietly(connection);

            throw exception;
        }
    }

    public PackInfo info() {

        return info;
    }

    @Override
    public void close() {

        closeQuietly(connection);
    }

    private static Connection readOnlyConnection(Path packFile) throws SQLException {

        SQLiteConfig config = new SQLiteConfig();
        config
                .setReadOnly(true);
        config
                .setOpenMode(SQLiteOpenMode.READONLY);
        config
                .enableLoadExtension(false);
        config
                .setBusyTimeout(BUSY_TIMEOUT_MILLIS);

        Connection connection = config
                .createConnection("jdbc:sqlite:" + packFile);

        try (Statement statement = connection
                .createStatement()) {
            statement
                    .execute("PRAGMA query_only = ON");
        }

        return connection;
    }

    private static PackInfo validate(Connection connection) throws SQLException {

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
                            "docVersion"
                    )
            );
            requireSearchIndex(connection);
            requireLogicalHash(
                    connection,
                    text(
                            meta,
                            "logicalContentSha256"
                    )
            );

            return new PackInfo(
                    text(
                            meta,
                            "packId"
                    ),
                    text(
                            meta,
                            "project"
                    ),
                    text(
                            meta,
                            "docVersion"
                    ),
                    text(
                            meta,
                            "buildId"
                    ),
                    meta
                            .get("packSchemaVersion")
                            .asInt(),
                    meta
                            .get("minReaderVersion")
                            .asInt(),
                    text(
                            meta,
                            "logicalContentSha256"
                    ),
                    meta
                            .get("capabilities")
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

    private static void requireHeader(Statement statement) throws SQLException {

        long applicationId = count(
                statement,
                "PRAGMA application_id"
        );

        if (applicationId != APPLICATION_ID) {
            throw new PackValidationException(
                    Reason.NOT_A_PACK,
                    "application_id is " + applicationId + ", expected " + APPLICATION_ID
            );
        }

        long userVersion = count(
                statement,
                "PRAGMA user_version"
        );

        if (userVersion != READER_VERSION) {
            throw new PackValidationException(
                    Reason.UNSUPPORTED_SCHEMA_VERSION,
                    "user_version " + userVersion + " is not supported by reader " + READER_VERSION
            );
        }
    }

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
                            Reason.INVALID_META,
                            "pack_meta " + rows
                                    .getString(1) + " is not JSON",
                            exception
                    );
                }
            }
        }

        for (String key : REQUIRED_META_KEYS) {
            if (!meta
                    .containsKey(key)) {
                throw new PackValidationException(
                        Reason.INVALID_META,
                        "pack_meta is missing " + key
                );
            }
        }

        if (!"DOCUMENT"
                .equals(text(
                        meta,
                        "packType"
                ))) {
            throw new PackValidationException(
                    Reason.INVALID_META,
                    "packType " + text(
                            meta,
                            "packType"
                    ) + " is not supported"
            );
        }

        if (!meta
                .get("embedding")
                .isNull()) {
            throw new PackValidationException(
                    Reason.INVALID_META,
                    "embedding must be null for reader " + READER_VERSION
            );
        }

        for (JsonNode capability : meta
                .get("capabilities")
                .values()) {
            if (!KNOWN_CAPABILITIES
                    .contains(capability
                            .asString())) {
                throw new PackValidationException(
                        Reason.INVALID_META,
                        "unknown capability " + capability
                                .asString()
                );
            }
        }

        return meta;
    }

    private static void requireVersions(Map<String, JsonNode> meta) {

        JsonNode schemaVersion = meta
                .get("packSchemaVersion");

        if (!schemaVersion
                .isInt()
                || schemaVersion
                        .asInt() != READER_VERSION) {
            throw new PackValidationException(
                    Reason.UNSUPPORTED_SCHEMA_VERSION,
                    "packSchemaVersion " + schemaVersion + " is not supported by reader " + READER_VERSION
            );
        }

        JsonNode minReaderVersion = meta
                .get("minReaderVersion");

        if (!minReaderVersion
                .isInt()
                || minReaderVersion
                        .asInt() > READER_VERSION) {
            throw new PackValidationException(
                    Reason.UNSUPPORTED_READER_VERSION,
                    "pack requires reader " + minReaderVersion + ", this reader is " + READER_VERSION
            );
        }
    }

    private static void requireIntegrity(Statement statement) throws SQLException {

        try (ResultSet rows = statement
                .executeQuery("PRAGMA integrity_check")) {
            rows
                    .next();

            if (!"ok"
                    .equals(rows
                            .getString(1))) {
                throw new PackValidationException(
                        Reason.CORRUPT,
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
                        Reason.CORRUPT,
                        "foreign_key_check failed in table " + rows
                                .getString(1)
                );
            }
        }
    }

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
                            Reason.INCONSISTENT_CONTENT,
                            "documents outside exact release " + docVersion
                    );
                }
            }
        }

        requireRowidOrder(statement);
    }

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
                            Reason.INCONSISTENT_CONTENT,
                            "chunk rowid " + rows
                                    .getLong(1) + " found where " + expected + " was expected"
                    );
                }

                expected++;
            }
        }
    }

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
                                Reason.SEARCH_INDEX_BROKEN,
                                "chunk " + rows
                                        .getLong(1) + " is not found by its own token"
                        );
                    }
                }

                return;
            }
        }
    }

    private static void requireLogicalHash(
            Connection connection,
            String recorded
    ) throws SQLException {

        String actual = LogicalContentHash
                .compute(connection);

        if (!actual
                .equals(recorded)) {
            throw new PackValidationException(
                    Reason.LOGICAL_HASH_MISMATCH,
                    "content hash " + actual + " does not match recorded " + recorded
            );
        }
    }

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
                    Reason.INCONSISTENT_CONTENT,
                    problem + " (" + found + ")"
            );
        }
    }

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

    private static String text(
            Map<String, JsonNode> meta,
            String key
    ) {

        JsonNode value = meta
                .get(key);

        if (value == null || !value
                .isString()) {
            throw new PackValidationException(
                    Reason.INVALID_META,
                    "pack_meta " + key + " must be a JSON string"
            );
        }

        return value
                .asString();
    }

    private static void closeQuietly(Connection connection) {

        if (connection == null) {
            return;
        }

        try {
            connection
                    .close();
        } catch (SQLException ignored) {
            // 원래 오류를 가리지 않도록 닫기 실패 무시
        }
    }
}
