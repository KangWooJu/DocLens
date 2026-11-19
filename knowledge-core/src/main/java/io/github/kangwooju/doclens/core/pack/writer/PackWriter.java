package io.github.kangwooju.doclens.core.pack.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.dto.CodeVariant;
import io.github.kangwooju.doclens.core.pack.dto.DocLink;
import io.github.kangwooju.doclens.core.pack.dto.PackChunk;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackMeta;
import io.github.kangwooju.doclens.core.pack.dto.PackSection;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.exception.PackWriteException;
import io.github.kangwooju.doclens.core.pack.support.LogicalContentHash;
import io.github.kangwooju.doclens.core.pack.support.PackFiles;

/**
 * Document Pack v1 Writer
 * 문서는 documentId 순, 섹션과 청크는 ordinal 순으로 받고 순서가 어긋나면 거절
 * finish에서 FTS 재구성, 무결성 검사, fsync, SHA-256 계산
 */
public final class PackWriter implements AutoCloseable {

    /** Pack 파일 상한(512 MiB) */
    public static final long MAX_PACK_BYTES = 536_870_912L;

    private static final String DDL_RESOURCE = "/pack/document-pack-v1.sql";
    private static final List<String> SIDE_FILES = List
            .of(
                    "-journal",
                    "-wal",
                    "-shm"
            );

    private final Path target;
    private final PackMeta meta;
    private final Connection connection;
    private final PreparedStatement insertDocument;
    private final PreparedStatement insertSection;
    private final PreparedStatement insertChunk;
    private final PreparedStatement insertVariant;
    private final PreparedStatement insertIdentifier;
    private final PreparedStatement insertLink;

    private String lastDocumentId;
    private long nextChunkRowid = 1;
    private long documentCount;
    private boolean finished;

    // 쓰기 연결 준비와 schema 생성
    private PackWriter(
            Path target,
            PackMeta meta,
            Connection connection
    ) throws SQLException {

        this.target = target;
        this.meta = meta;
        this.connection = connection;
        this.insertDocument = connection
                .prepareStatement("INSERT INTO documents VALUES(?,?,?,?,'EXACT_RELEASE',?,?,?,?)");
        this.insertSection = connection
                .prepareStatement("INSERT INTO sections VALUES(?,?,?,?,?,?)");
        this.insertChunk = connection
                .prepareStatement("INSERT INTO chunks VALUES(?,?,?,?,?,?,?,?,?)");
        this.insertVariant = connection
                .prepareStatement("INSERT INTO code_variants VALUES(?,?,?,?,?,?,?,?)");
        this.insertIdentifier = connection
                .prepareStatement("INSERT INTO identifiers VALUES(?,?)");
        this.insertLink = connection
                .prepareStatement("INSERT INTO doc_links VALUES(?,?,?)");
    }

    /** 새 Pack 파일 생성과 Writer 열기, target은 없는 경로만 허용 */
    public static PackWriter create(
            Path target,
            PackMeta meta
    ) {

        if (Files
                .exists(target)) {
            throw new PackWriteException("target already exists: " + target);
        }

        Connection connection = null;

        try {
            connection = DriverManager
                    .getConnection("jdbc:sqlite:" + target);

            try (Statement statement = connection
                    .createStatement()) {
                for (String sql : ddlStatements()) {
                    statement
                            .execute(sql);
                }
            }

            connection
                    .setAutoCommit(false);

            return new PackWriter(
                    target,
                    meta,
                    connection
            );
        } catch (SQLException | IOException exception) {
            closeQuietly(connection);
            deleteQuietly(target);

            throw new PackWriteException(
                    "cannot create pack: " + target,
                    exception
            );
        }
    }

    /** 문서 하나와 섹션, 청크 추가, 거절 시 Writer 종료와 미완성 파일 삭제 */
    public void add(PackDocument document) {

        requireOpen();

        try {
            requireOrderAndVersion(document);
            insertDocument(document);
            insertSections(document);
        } catch (SQLException exception) {
            close();

            throw rejected(
                    "document " + document
                            .documentId(),
                    exception
            );
        } catch (PackWriteException exception) {
            close();

            throw exception;
        }

        lastDocumentId = document
                .documentId();
        documentCount++;
    }

    // 문서 순서(documentId 오름차순)와 문서 버전 일치 검사
    private void requireOrderAndVersion(PackDocument document) {

        if (lastDocumentId != null
                && CanonicalJson
                        .compareCodePoints(
                                lastDocumentId,
                                document
                                        .documentId()
                        ) >= 0) {
            throw new PackWriteException(
                    "documents must be added in ascending documentId order: "
                            + document
                                    .documentId()
                            + " after "
                            + lastDocumentId
            );
        }

        if (!document
                .exactVersion()
                .equals(meta
                        .docVersion())) {
            throw new PackWriteException(
                    "document exactVersion "
                            + document
                                    .exactVersion()
                            + " does not match pack docVersion "
                            + meta
                                    .docVersion()
            );
        }
    }

    /** 메타데이터 쓰기와 파일 완결 */
    public PackWriteResult finish() {

        requireOpen();
        finished = true;

        try {
            writeMeta();
            String logicalContentSha256 = LogicalContentHash
                    .compute(connection);
            insertMeta(
                    "logicalContentSha256",
                    CanonicalJson
                            .write(logicalContentSha256)
            );
            connection
                    .commit();
            connection
                    .setAutoCommit(true);

            verifyDatabase();
            closeStatementsAndConnection();
            verifyNoSideFiles();
            fsync();

            long size = Files
                    .size(target);

            if (size > MAX_PACK_BYTES) {
                throw new PackWriteException("pack exceeds " + MAX_PACK_BYTES + " bytes: " + size);
            }

            return new PackWriteResult(
                    target,
                    PackFiles
                            .sha256(target),
                    size,
                    logicalContentSha256
            );
        } catch (SQLException | IOException | UncheckedIOException | PackWriteException exception) {
            closeStatementsAndConnection();
            deleteQuietly(target);

            if (exception instanceof PackWriteException rejected) {
                throw rejected;
            }

            throw new PackWriteException(
                    "cannot finish pack: " + target,
                    exception
            );
        }
    }

    /** finish 전 닫기 시 미완성 파일 삭제 */
    @Override
    public void close() {

        if (finished) {
            return;
        }

        finished = true;
        closeStatementsAndConnection();
        deleteQuietly(target);
    }

    // documents 행 저장
    private void insertDocument(PackDocument document) throws SQLException {

        insertDocument
                .setString(
                        1,
                        document
                                .documentId()
                );
        insertDocument
                .setString(
                        2,
                        document
                                .url()
                );
        insertDocument
                .setString(
                        3,
                        document
                                .title()
                );
        insertDocument
                .setString(
                        4,
                        document
                                .documentType()
                                .wireValue()
                );
        insertDocument
                .setString(
                        5,
                        document
                                .exactVersion()
                );
        insertDocument
                .setString(
                        6,
                        document
                                .sourceSnapshotId()
                );
        insertDocument
                .setString(
                        7,
                        document
                                .rawSha256()
                );
        insertDocument
                .setString(
                        8,
                        canonicalJsonColumn(
                                document
                                        .sourceMapJson(),
                                "document sourceMapJson"
                        )
                );
        insertDocument
                .executeUpdate();
    }

    // 섹션 저장, ordinal 순서와 ID 중복, 부모 선언 순서 검사
    private void insertSections(PackDocument document) throws SQLException {

        Set<String> sectionsSoFar = new HashSet<>();
        int previousOrdinal = -1;

        for (PackSection section : document
                .sections()) {
            if (section
                    .ordinal() <= previousOrdinal) {
                throw new PackWriteException(
                        "sections must be in ascending ordinal order in document "
                                + document
                                        .documentId()
                );
            }

            // 부모 섹션은 같은 문서의 앞쪽 섹션만 허용(순환, 다른 문서 참조 차단)
            if (section
                    .parentSectionId() != null
                    && !sectionsSoFar
                            .contains(section
                                    .parentSectionId())) {
                throw new PackWriteException(
                        "parent section "
                                + section
                                        .parentSectionId()
                                + " must be declared earlier in the same document"
                );
            }

            insertSection
                    .setString(
                            1,
                            section
                                    .sectionId()
                    );
            insertSection
                    .setString(
                            2,
                            document
                                    .documentId()
                    );
            insertSection
                    .setString(
                            3,
                            section
                                    .parentSectionId()
                    );
            insertSection
                    .setString(
                            4,
                            section
                                    .anchor()
                    );
            insertSection
                    .setString(
                            5,
                            CanonicalJson
                                    .write(section
                                            .headingPath())
                    );
            insertSection
                    .setInt(
                            6,
                            section
                                    .ordinal()
                    );
            insertSection
                    .executeUpdate();

            insertChunks(
                    document,
                    section
            );

            sectionsSoFar
                    .add(section
                            .sectionId());
            previousOrdinal = section
                    .ordinal();
        }
    }

    // 청크 저장, ordinal 순서 검사와 하위 행 저장
    private void insertChunks(
            PackDocument document,
            PackSection section
    ) throws SQLException {

        int previousOrdinal = -1;

        for (PackChunk chunk : section
                .chunks()) {
            if (chunk
                    .ordinal() <= previousOrdinal) {
                throw new PackWriteException(
                        "chunks must be in ascending ordinal order in section "
                                + section
                                        .sectionId()
                );
            }

            long rowid = nextChunkRowid++;

            insertChunk
                    .setLong(
                            1,
                            rowid
                    );
            insertChunk
                    .setString(
                            2,
                            chunk
                                    .chunkId()
                    );
            insertChunk
                    .setString(
                            3,
                            document
                                    .documentId()
                    );
            insertChunk
                    .setString(
                            4,
                            section
                                    .sectionId()
                    );
            insertChunk
                    .setInt(
                            5,
                            chunk
                                    .ordinal()
                    );
            insertChunk
                    .setString(
                            6,
                            chunk
                                    .content()
                    );
            insertChunk
                    .setString(
                            7,
                            chunk
                                    .headingPath()
                    );
            insertChunk
                    .setString(
                            8,
                            canonicalJsonColumn(
                                    chunk
                                            .blocksJson(),
                                    "chunk blocksJson"
                            )
                    );
            insertChunk
                    .setString(
                            9,
                            canonicalJsonColumn(
                                    chunk
                                            .sourceMapJson(),
                                    "chunk sourceMapJson"
                            )
                    );
            insertChunk
                    .executeUpdate();

            insertVariants(
                    chunk,
                    rowid
            );
            insertIdentifiers(
                    chunk,
                    rowid
            );
            insertLinks(
                    chunk,
                    rowid
            );

            previousOrdinal = chunk
                    .ordinal();
        }
    }

    // 코드 변형 저장, 블록마다 기본 변형 1개 검사
    private void insertVariants(
            PackChunk chunk,
            long rowid
    ) throws SQLException {

        Map<Integer, Integer> defaultsPerBlock = new LinkedHashMap<>();

        for (CodeVariant variant : chunk
                .codeVariants()) {
            defaultsPerBlock
                    .merge(
                            variant
                                    .blockIndex(),
                            variant
                                    .isDefault() ? 1 : 0,
                            Integer::sum
                    );

            insertVariant
                    .setLong(
                            1,
                            rowid
                    );
            insertVariant
                    .setInt(
                            2,
                            variant
                                    .blockIndex()
                    );
            insertVariant
                    .setString(
                            3,
                            variant
                                    .groupType()
                                    .wireValue()
                    );
            insertVariant
                    .setString(
                            4,
                            variant
                                    .variant()
                    );
            insertVariant
                    .setInt(
                            5,
                            variant
                                    .ordinal()
                    );
            insertVariant
                    .setInt(
                            6,
                            variant
                                    .isDefault() ? 1 : 0
                    );
            insertVariant
                    .setString(
                            7,
                            variant
                                    .code()
                    );
            insertVariant
                    .setString(
                            8,
                            canonicalJsonColumn(
                                    variant
                                            .sourceMapJson(),
                                    "variant sourceMapJson"
                            )
                    );
            insertVariant
                    .executeUpdate();
        }

        defaultsPerBlock
                .forEach((blockIndex, defaults) -> {
                    if (defaults != 1) {
                        throw new PackWriteException(
                                "code block "
                                        + blockIndex
                                        + " of chunk "
                                        + chunk
                                                .chunkId()
                                        + " must have exactly one default variant, found "
                                        + defaults
                        );
                    }
                });
    }

    // 청크 식별자 저장
    private void insertIdentifiers(
            PackChunk chunk,
            long rowid
    ) throws SQLException {

        for (String identifier : chunk
                .identifiers()) {
            insertIdentifier
                    .setString(
                            1,
                            identifier
                    );
            insertIdentifier
                    .setLong(
                            2,
                            rowid
                    );
            insertIdentifier
                    .executeUpdate();
        }
    }

    // 청크 문서 링크 저장
    private void insertLinks(
            PackChunk chunk,
            long rowid
    ) throws SQLException {

        for (DocLink link : chunk
                .docLinks()) {
            insertLink
                    .setLong(
                            1,
                            rowid
                    );
            insertLink
                    .setString(
                            2,
                            link
                                    .targetUrl()
                    );
            insertLink
                    .setString(
                            3,
                            link
                                    .targetAnchor()
                    );
            insertLink
                    .executeUpdate();
        }
    }

    // pack_meta 16개 키 저장(logicalContentSha256 제외)
    private void writeMeta() throws SQLException {

        Map<String, Object> values = new LinkedHashMap<>();
        values
                .put(
                        "packId",
                        meta
                                .packId()
                );
        values
                .put(
                        "packType",
                        "DOCUMENT"
                );
        values
                .put(
                        "project",
                        meta
                                .project()
                );
        values
                .put(
                        "docVersion",
                        meta
                                .docVersion()
                );
        values
                .put(
                        "buildId",
                        meta
                                .buildId()
                );
        values
                .put(
                        "packSchemaVersion",
                        1
                );
        values
                .put(
                        "minReaderVersion",
                        1
                );
        values
                .put(
                        "inputSnapshotHash",
                        meta
                                .inputSnapshotHash()
                );
        values
                .put(
                        "processingConfigHash",
                        meta
                                .processingConfigHash()
                );
        values
                .put(
                        "parserPolicyVersion",
                        meta
                                .parserPolicyVersion()
                );
        values
                .put(
                        "chunkerPolicyVersion",
                        meta
                                .chunkerPolicyVersion()
                );
        values
                .put(
                        "sourceAttribution",
                        meta
                                .sourceAttribution()
                );
        values
                .put(
                        "sourceSnapshotIds",
                        meta
                                .sourceSnapshotIds()
                );
        values
                .put(
                        "capabilities",
                        List
                                .of(
                                        "BM25",
                                        "SECTIONS",
                                        "VARIANTS"
                                )
                );
        values
                .put(
                        "embedding",
                        null
                );
        values
                .put(
                        "createdBy",
                        meta
                                .createdBy()
                );

        for (Map.Entry<String, Object> entry : values
                .entrySet()) {
            insertMeta(
                    entry
                            .getKey(),
                    CanonicalJson
                            .write(entry
                                    .getValue())
            );
        }
    }

    // pack_meta 키 하나의 canonical JSON 값 저장
    private void insertMeta(
            String key,
            String jsonValue
    ) throws SQLException {

        try (PreparedStatement statement = connection
                .prepareStatement("INSERT INTO pack_meta VALUES(?,?)")) {
            statement
                    .setString(
                            1,
                            key
                    );
            statement
                    .setString(
                            2,
                            jsonValue
                    );
            statement
                    .executeUpdate();
        }
    }

    // FTS 재구성, 무결성 검사, 행 수 확인
    private void verifyDatabase() throws SQLException {

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
                    nextChunkRowid - 1
            );
        }
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
    private void verifyNoSideFiles() {

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
    private void fsync() throws IOException {

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

    // JSON 컬럼 값의 canonical 변환, 잘못된 JSON 거절
    private static String canonicalJsonColumn(
            String json,
            String name
    ) {

        try {
            return CanonicalJson
                    .canonicalize(json);
        } catch (IllegalArgumentException exception) {
            throw new PackWriteException(
                    name + " is not valid canonical JSON input: " + exception
                            .getMessage(),
                    exception
            );
        }
    }

    // 리소스 DDL 파일의 문장 단위 분리
    private static List<String> ddlStatements() throws IOException {

        try (InputStream in = PackWriter.class
                .getResourceAsStream(DDL_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing resource " + DDL_RESOURCE);
            }

            String ddl = new String(
                    in
                            .readAllBytes(),
                    StandardCharsets.UTF_8
            );

            return Arrays
                    .stream(ddl
                            .split(";\\s*\\n"))
                    .map(String::strip)
                    .filter(sql -> !sql
                            .isEmpty())
                    .toList();
        }
    }

    // SQLite 제약 위반의 PackWriteException 변환
    private PackWriteException rejected(
            String what,
            SQLException exception
    ) {

        return new PackWriteException(
                what + " rejected by pack constraints: " + exception
                        .getMessage(),
                exception
        );
    }

    // finish나 close 뒤 사용 거절
    private void requireOpen() {

        if (finished) {
            throw new PackWriteException("writer is already finished or closed");
        }
    }

    // 준비된 문장과 연결 종료
    private void closeStatementsAndConnection() {

        for (Statement statement : List
                .of(
                        insertDocument,
                        insertSection,
                        insertChunk,
                        insertVariant,
                        insertIdentifier,
                        insertLink
                )) {
            closeQuietly(statement);
        }

        closeQuietly(connection);
    }

    // 예외 없는 자원 닫기
    private static void closeQuietly(AutoCloseable closeable) {

        if (closeable == null) {
            return;
        }

        try {
            closeable
                    .close();
        } catch (Exception ignored) {
            // 정리 단계의 닫기 실패는 원래 오류를 가리지 않도록 무시
        }
    }

    // 예외 없는 미완성 파일 삭제
    private static void deleteQuietly(Path path) {

        try {
            Files
                    .deleteIfExists(path);

            for (String suffix : SIDE_FILES) {
                Files
                        .deleteIfExists(path
                                .resolveSibling(path
                                        .getFileName() + suffix));
            }
        } catch (IOException ignored) {
            // 미완성 파일 정리 실패는 원래 오류를 가리지 않도록 무시
        }
    }
}
