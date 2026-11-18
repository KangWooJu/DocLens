package io.github.kangwooju.doclens.core.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.kangwooju.doclens.core.json.CanonicalJson;

/**
 * Pack 논리 내용 해시(logicalContentSha256)
 *
 * <p>정해진 테이블 순서와 PK(BINARY) 순서로 읽은 모든 행을 한 행당
 * `{"row":{...},"table":"..."}` canonical JSON 한 줄(LF 종료)로 만든 뒤의 SHA-256
 * 같은 내용이면 buildId·파일 경로·물리 바이트와 무관한 같은 값
 *
 * <p>제외: pack_meta의 buildId(무작위)와 logicalContentSha256 자신, 파생 색인 chunks_fts
 * BLOB은 소문자 hex 문자열로 표현
 */
final class LogicalContentHash {

    private static final Set<String> EXCLUDED_META_KEYS = Set
            .of(
                    "buildId",
                    "logicalContentSha256"
            );

    private static final Map<String, String> TABLE_QUERIES = orderedQueries();

    private LogicalContentHash() {
    }

    static String compute(Connection connection) throws SQLException {

        MessageDigest digest = newDigest();

        try (Statement statement = connection
                .createStatement()) {
            for (Map.Entry<String, String> table : TABLE_QUERIES
                    .entrySet()) {
                try (ResultSet rows = statement
                        .executeQuery(table
                                .getValue())) {
                    digestRows(
                            digest,
                            table
                                    .getKey(),
                            rows
                    );
                }
            }
        }

        return "sha256:" + HexFormat
                .of()
                .formatHex(digest
                        .digest());
    }

    private static void digestRows(
            MessageDigest digest,
            String table,
            ResultSet rows
    ) throws SQLException {

        ResultSetMetaData columns = rows
                .getMetaData();

        while (rows
                .next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            int columnCount = columns
                    .getColumnCount();

            for (int index = 1; index <= columnCount; index++) {
                row
                        .put(
                                columns
                                        .getColumnLabel(index),
                                columnValue(
                                        rows,
                                        index
                                )
                        );
            }

            if ("pack_meta"
                    .equals(table)
                    && EXCLUDED_META_KEYS
                            .contains((String) row
                                    .get("key"))) {
                continue;
            }

            String line = CanonicalJson
                    .write(Map
                            .of(
                                    "table",
                                    table,
                                    "row",
                                    row
                            )) + "\n";
            digest
                    .update(line
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Object columnValue(
            ResultSet rows,
            int index
    ) throws SQLException {

        Object value = rows
                .getObject(index);

        if (value == null) {
            return null;
        }

        if (rows
                .getMetaData()
                .getColumnType(index) == Types.BLOB
                || value instanceof byte[]) {
            return HexFormat
                    .of()
                    .formatHex(rows
                            .getBytes(index));
        }

        if (value instanceof Number number) {
            return number
                    .longValue();
        }

        return value
                .toString();
    }

    private static Map<String, String> orderedQueries() {

        Map<String, String> queries = new LinkedHashMap<>();
        List
                .of(
                        "pack_meta|key",
                        "documents|document_id",
                        "sections|section_id",
                        "chunks|rowid",
                        "code_variants|chunk_rowid, block_index, variant",
                        "identifiers|identifier, chunk_rowid",
                        "doc_links|from_chunk_rowid, target_url, target_anchor",
                        "chunk_vectors|chunk_rowid",
                        "vector_omissions|chunk_rowid"
                )
                .forEach(spec -> {
                    String[] parts = spec
                            .split("\\|");
                    String order = Arrays
                            .stream(parts[1]
                                    .split(",\\s*"))
                            .map(column -> column + " COLLATE BINARY")
                            .collect(Collectors
                                    .joining(", "));
                    queries
                            .put(
                                    parts[0],
                                    "SELECT * FROM " + parts[0] + " ORDER BY " + order
                            );
                });

        return queries;
    }

    private static MessageDigest newDigest() {

        try {
            return MessageDigest
                    .getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
