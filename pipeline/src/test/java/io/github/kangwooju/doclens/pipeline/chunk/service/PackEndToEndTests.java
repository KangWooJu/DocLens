package io.github.kangwooju.doclens.pipeline.chunk.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackMeta;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.reader.PackReader;
import io.github.kangwooju.doclens.core.pack.writer.PackWriter;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.service.DocumentParseService;

@DisplayName("HTML에서 Pack 파일과 검색까지")
class PackEndToEndTests {

    @TempDir
    Path directory;

    @Test
    @DisplayName("파싱, 청크, Pack 쓰기 뒤 Reader 검증 통과, FTS와 식별자 정확 일치 조회")
    void writesSearchablePack() throws Exception {

        PackDocument document = ChunkServiceTests
                .chunk(ParseFixtures
                        .html("reference.html"));
        Path target = directory
                .resolve("reference.pack");
        PackWriteResult result;

        try (PackWriter writer = PackWriter
                .create(
                        target,
                        meta()
                )) {
            writer
                    .add(document);
            result = writer
                    .finish();
        }

        assertTrue(result
                .sizeBytes() > 0);

        try (PackReader reader = PackReader
                .open(target)) {
            List<String> matches = reader
                    .query(connection -> {
                        try (PreparedStatement statement = connection
                                .prepareStatement("SELECT c.heading_path FROM chunks_fts f JOIN chunks c ON c.rowid = f.rowid"
                                        + " WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts), c.chunk_id")) {
                            statement
                                    .setString(
                                            1,
                                            "\"banner\""
                                    );

                            return headings(statement);
                        }
                    });
            List<String> exact = reader
                    .query(connection -> {
                        try (PreparedStatement statement = connection
                                .prepareStatement("SELECT c.heading_path FROM identifiers i JOIN chunks c ON c.rowid = i.chunk_rowid"
                                        + " WHERE i.identifier = ?")) {
                            statement
                                    .setString(
                                            1,
                                            "@SpringBootApplication"
                                    );

                            return headings(statement);
                        }
                    });

            assertEquals(
                    List
                            .of("Sample Settings > Settings Files > YAML Files"),
                    matches
            );
            assertEquals(
                    List
                            .of("Sample Settings > Settings Files"),
                    exact
            );
        }
    }

    // 조회 결과의 heading 경로 목록
    private static List<String> headings(PreparedStatement statement) throws SQLException {

        List<String> headings = new ArrayList<>();

        try (ResultSet rows = statement
                .executeQuery()) {
            while (rows
                    .next()) {
                headings
                        .add(rows
                                .getString(1));
            }
        }

        return headings;
    }

    // 시험 Pack 메타데이터
    private static PackMeta meta() {

        return new PackMeta(
                "spring-boot-4.1.2-test",
                "spring-boot",
                "4.1.2",
                "11111111-1111-4111-8111-111111111111",
                "sha256:" + "6"
                        .repeat(64),
                "sha256:" + "7"
                        .repeat(64),
                DocumentParseService.PARSER_POLICY_VERSION,
                ChunkService.CHUNKER_POLICY_VERSION,
                "Spring documentation, Apache-2.0",
                List
                        .of("sha256:" + "5"
                                .repeat(64)),
                "pipeline-test"
        );
    }
}
