package io.github.kangwooju.doclens.mcp.search.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Repository;

import io.github.kangwooju.doclens.mcp.search.converter.HeadingPathConverter;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkHit;
import io.github.kangwooju.doclens.mcp.search.dto.SearchTerms;
import lombok.RequiredArgsConstructor;

/**
 * Pack 하나에서 청크 후보를 찾는 검색 SQL(F12)
 *
 * <p>BM25 갈래와 정확 식별자 갈래를 개수 제한 전에 합치고 청크 rowid로 하나만 남김
 * <p>정렬: 정확 일치 먼저, bm25 오름차순(점수 없음은 뒤), chunkId BINARY
 * <p>documentTypes 조건은 합친 뒤 두 갈래에 똑같이 적용
 */
@Repository
@RequiredArgsConstructor
public class ChunkSearchRepository {

    private final HeadingPathConverter headingPathConverter;

    /** 정렬된 청크 후보, 최대 limit개 */
    public List<ChunkHit> find(
            Connection connection,
            SearchTerms terms,
            List<String> documentTypes,
            int limit
    ) throws SQLException {

        List<Object> parameters = new ArrayList<>();
        String sql = sql(
                terms,
                documentTypes,
                parameters
        );
        parameters
                .add(limit);

        try (PreparedStatement statement = connection
                .prepareStatement(sql)) {
            int count = parameters
                    .size();

            for (int index = 0; index < count; index++) {
                statement
                        .setObject(
                                index + 1,
                                parameters
                                        .get(index)
                        );
            }

            try (ResultSet rows = statement
                    .executeQuery()) {
                List<ChunkHit> hits = new ArrayList<>();

                while (rows
                        .next()) {
                    hits
                            .add(hit(rows));
                }

                return hits;
            }
        }
    }

    // 입력에 맞춘 검색 SQL과 순서대로 채운 바인딩 값
    private static String sql(
            SearchTerms terms,
            List<String> documentTypes,
            List<Object> parameters
    ) {

        String lexical = "SELECT NULL AS chunk_rowid, NULL AS score WHERE 0";

        if (terms
                .matchExpression() != null) {
            lexical = "SELECT rowid AS chunk_rowid, bm25(chunks_fts) AS score "
                    + "FROM chunks_fts WHERE chunks_fts MATCH ?";
            parameters
                    .add(terms
                            .matchExpression());
        }

        String exact = "SELECT NULL AS chunk_rowid WHERE 0";

        if (!terms
                .identifiers()
                .isEmpty()) {
            exact = "SELECT DISTINCT chunk_rowid FROM identifiers WHERE identifier IN ("
                    + placeholders(terms
                            .identifiers()
                            .size()) + ")";
            parameters
                    .addAll(terms
                            .identifiers());
        }

        String typeFilter = "";

        if (!documentTypes
                .isEmpty()) {
            typeFilter = " WHERE d.document_type IN (" + placeholders(documentTypes
                    .size()) + ")";
            parameters
                    .addAll(documentTypes);
        }

        return "WITH lexical AS MATERIALIZED (" + lexical + "),"
                + " exact AS MATERIALIZED (" + exact + "),"
                + " hits AS (SELECT chunk_rowid FROM lexical UNION SELECT chunk_rowid FROM exact)"
                + " SELECT c.chunk_id, c.document_id, c.section_id, c.content,"
                + " d.url, d.applicability, d.exact_version, s.anchor, s.heading_path,"
                + " l.score, e.chunk_rowid IS NOT NULL AS exact_match"
                + " FROM hits h"
                + " JOIN chunks c ON c.rowid = h.chunk_rowid"
                + " JOIN documents d ON d.document_id = c.document_id"
                + " JOIN sections s ON s.section_id = c.section_id"
                + " LEFT JOIN lexical l ON l.chunk_rowid = h.chunk_rowid"
                + " LEFT JOIN exact e ON e.chunk_rowid = h.chunk_rowid"
                + typeFilter
                + " ORDER BY exact_match DESC, l.score IS NULL, l.score, c.chunk_id COLLATE BINARY"
                + " LIMIT ?";
    }

    // 결과 행 하나의 ChunkHit 변환
    private ChunkHit hit(ResultSet rows) throws SQLException {

        double score = rows
                .getDouble("score");
        Double lexicalScore = rows
                .wasNull()
                ? null
                : score;

        return new ChunkHit(
                rows
                        .getString("chunk_id"),
                rows
                        .getString("document_id"),
                rows
                        .getString("section_id"),
                rows
                        .getString("content"),
                rows
                        .getString("url"),
                rows
                        .getString("applicability"),
                rows
                        .getString("exact_version"),
                rows
                        .getString("anchor"),
                headingPathConverter
                        .toList(rows
                                .getString("heading_path")),
                lexicalScore,
                rows
                        .getBoolean("exact_match")
        );
    }

    // 바인딩 자리 count개(`?, ?, ...`)
    private static String placeholders(int count) {

        return String
                .join(
                        ", ",
                        Collections
                                .nCopies(
                                        count,
                                        "?"
                                )
                );
    }
}
