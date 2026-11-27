package io.github.kangwooju.doclens.core.pack.reader;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import io.github.kangwooju.doclens.core.pack.dto.PackInfo;
import io.github.kangwooju.doclens.core.pack.exception.PackValidationException;
import io.github.kangwooju.doclens.core.pack.type.PackValidationReason;

/**
 * 검증 후 읽기 전용으로 여는 Pack Reader
 * 검색 SQL은 사용하는 쪽에서 작성, Reader는 검증된 연결만 제공
 */
public final class PackReader implements AutoCloseable {

    /** 이 Reader가 이해하는 Pack schema와 Reader 버전 */
    public static final int READER_VERSION = 1;

    private static final int BUSY_TIMEOUT_MILLIS = 1000;

    private final Connection connection;
    private final PackInfo info;

    // 검증을 마친 연결과 Pack 정보 보관
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
                    PackValidationReason.NOT_A_FILE,
                    "not a regular file (symbolic links are refused): " + packFile
            );
        }

        Connection connection = null;

        try {
            connection = readOnlyConnection(packFile);
            PackInfo info = PackValidator
                    .validate(connection);

            return new PackReader(
                    connection,
                    info
            );
        } catch (SQLException exception) {
            closeQuietly(connection);

            throw new PackValidationException(
                    PackValidationReason.CORRUPT,
                    "cannot read pack " + packFile + ": " + exception
                            .getMessage(),
                    exception
            );
        } catch (RuntimeException exception) {
            closeQuietly(connection);

            throw exception;
        }
    }

    /** 검증한 Pack 정보 */
    public PackInfo info() {

        return info;
    }

    /** 검증된 읽기 전용 연결로 조회 실행, 한 Reader의 조회는 차례로 실행 */
    public synchronized <T> T query(Query<T> query) throws SQLException {

        // 연결 하나를 조회마다 잠가서 사용
        return query
                .run(connection);
    }

    /** 연결 닫기 */
    @Override
    public synchronized void close() {

        closeQuietly(connection);
    }

    /** Reader 연결로 실행하는 조회, 연결 종료는 Reader 담당 */
    @FunctionalInterface
    public interface Query<T> {

        /** 연결을 받아 결과 반환 */
        T run(Connection connection) throws SQLException;
    }

    // 읽기 전용 연결 생성(확장 로드 금지, query_only)
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

    // 예외 없는 연결 닫기
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
