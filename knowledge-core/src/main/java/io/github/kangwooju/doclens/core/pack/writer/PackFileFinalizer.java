package io.github.kangwooju.doclens.core.pack.writer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.exception.PackWriteException;
import io.github.kangwooju.doclens.core.pack.support.PackFiles;

/** 다 쓴 Pack 파일의 검사와 마무리 */
final class PackFileFinalizer {

    static final List<String> SIDE_FILES = List
            .of(
                    "-journal",
                    "-wal",
                    "-shm"
            );

    // 정적 메서드 전용
    private PackFileFinalizer() {
    }

    // FTS 재구성, 무결성 검사, 행 수 확인
    static void verifyDatabase(
            Connection connection,
            long documentCount,
            long chunkCount
    ) throws SQLException {

        try (Statement statement = connection
                .createStatement()) {
            statement
                    .execute("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')");
            statement
                    .execute("INSERT INTO chunks_fts(chunks_fts, rank) VALUES('integrity-check', 1)");

            try (ResultSet rows = statement
                    .executeQuery("PRAGMA foreign_key_check")) {
                if (rows
                        .next()) {
                    throw new PackWriteException("foreign_key_check failed in table " + rows
                            .getString(1));
                }
            }

            try (ResultSet rows = statement
                    .executeQuery("PRAGMA integrity_check")) {
                rows
                        .next();

                if (!"ok"
                        .equals(rows
                                .getString(1))) {
                    throw new PackWriteException("integrity_check failed: " + rows
                            .getString(1));
                }
            }

            requireCount(
                    statement,
                    "SELECT count(*) FROM documents",
                    documentCount
            );
            requireCount(
                    statement,
                    "SELECT count(*) FROM chunks",
                    chunkCount
            );
        }
    }

    // 연결을 닫은 뒤 부속 파일 확인, fsync, 크기 상한, 체크섬 계산
    static PackWriteResult complete(
            Path target,
            String logicalContentSha256
    ) throws IOException {

        verifyNoSideFiles(target);
        fsync(target);

        long size = Files
                .size(target);

        if (size > PackWriter.MAX_PACK_BYTES) {
            throw new PackWriteException("pack exceeds " + PackWriter.MAX_PACK_BYTES + " bytes: " + size);
        }

        return new PackWriteResult(
                target,
                PackFiles
                        .sha256(target),
                size,
                logicalContentSha256
        );
    }

    // 조회 결과 개수와 기대값 일치 검사
    private static void requireCount(
            Statement statement,
            String sql,
            long expected
    ) throws SQLException {

        try (ResultSet rows = statement
                .executeQuery(sql)) {
            rows
                    .next();

            if (rows
                    .getLong(1) != expected) {
                throw new PackWriteException(sql + " returned " + rows
                        .getLong(1) + ", expected " + expected);
            }
        }
    }

    // journal, WAL, SHM 파일이 남지 않았는지 확인
    private static void verifyNoSideFiles(Path target) {

        for (String suffix : SIDE_FILES) {
            Path side = target
                    .resolveSibling(target
                            .getFileName() + suffix);

            if (Files
                    .exists(side)) {
                throw new PackWriteException("journal side file remains: " + side);
            }
        }
    }

    // Pack 파일 내용의 디스크 기록 강제
    private static void fsync(Path target) throws IOException {

        try (FileChannel channel = FileChannel
                .open(
                        target,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                )) {
            channel
                    .force(true);
        }
    }
}
