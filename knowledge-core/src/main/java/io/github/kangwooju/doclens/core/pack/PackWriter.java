package io.github.kangwooju.doclens.core.pack;

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

/**
 * Document Pack v1 streaming Writer.
 *
 * <p>문서는 documentId 오름차순(code point), 섹션·청크는 ordinal 오름차순으로 넣음.
 * 순서가 어긋나면 거절. 전체를 메모리에 올리지 않음.
 *
 * <p>완결: commit → FTS 재구성·검사 → foreign_key_check·integrity_check → 연결 종료
 * → journal 부재 확인 → fsync → SHA-256. 최종 경로 이동·업로드는 호출 측 책임.
 */
public final class PackWriter implements AutoCloseable {

    /** Pack 파일 상한(512 MiB). */
    public static final long MAX_PACK_BYTES = 536_870_912L;

    private static final String DDL_RESOURCE = "/pack/document-pack-v1.sql";
    private static final List<String> SIDE_FILES = List.of(
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

    private PackWriter(
            Path target,
            PackMeta meta,
            Connection connection
    ) throws SQLException {

        this.target = target;
        this.meta = meta;
        this.connection = connection;
        this.insertDocument = connection.prepareStatement(
                "INSERT INTO documents VALUES(?,?,?,?,'EXACT_RELEASE',?,?,?,?)"
        );
        this.insertSection = connection.prepareStatement(
                "INSERT INTO sections VALUES(?,?,?,?,?,?)"
        );
        this.insertChunk = connection.prepareStatement(
                "INSERT INTO chunks VALUES(?,?,?,?,?,?,?,?,?)"
        );
        this.insertVariant = connection.prepareStatement(
                "INSERT INTO code_variants VALUES(?,?,?,?,?,?,?,?)"
        );
        this.insertIdentifier = connection.prepareStatement(
                "INSERT INTO identifiers VALUES(?,?)"
        );
        this.insertLink = connection.prepareStatement(
                "INSERT INTO doc_links VALUES(?,?,?)"
        );
    }

    /** 새 Pack 파일을 만들고 Writer를 연다. target은 아직 없어야 함. */
    public static PackWriter create(
            Path target,
            PackMeta meta
    ) {

        if (Files.exists(target)) {
            throw new PackWriteException("target already exists: " + target);
        }

        Connection connection = null;

        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + target);

            try (Statement statement = connection.createStatement()) {
                for (String sql : ddlStatements()) {
                    statement.execute(sql);
                }
            }

            connection.setAutoCommit(false);

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

    /** 문서 하나를 섹션·청크와 함께 추가. 거절되면 Writer를 닫고 미완성 파일을 지움. */
    public void add(PackDocument document) {

        requireOpen();

        try {
            requireOrderAndVersion(document);
            insertDocument(document);
            insertSections(document);
        } catch (SQLException exception) {
            close();

            throw rejected(
                    "document " + document.documentId(),
                    exception
            );
        } catch (PackWriteException exception) {
            close();

            throw exception;
        }

        lastDocumentId = document.documentId();
        documentCount++;
    }

    private void requireOrderAndVersion(PackDocument document) {

        if (lastDocumentId != null
                && CanonicalJson.compareCodePoints(
                        lastDocumentId,
                        document.documentId()
                ) >= 0) {
            throw new PackWriteException(
                    "documents must be added in ascending documentId order: "
                            + document.documentId()
                            + " after "
                            + lastDocumentId
            );
        }

        if (!document
                .exactVersion()
                .equals(meta.docVersion())) {
            throw new PackWriteException(
                    "document exactVersion "
                            + document.exactVersion()
                            + " does not match pack docVersion "
                            + meta.docVersion()
            );
        }
    }

    /** 메타데이터를 쓰고 파일을 완결. */
    public PackWriteResult finish() {

        requireOpen();
        finished = true;

        try {
            writeMeta();
            String logicalContentSha256 = LogicalContentHash.compute(connection);
            insertMeta(
                    "logicalContentSha256",
                    CanonicalJson.write(logicalContentSha256)
            );
            connection.commit();
            connection.setAutoCommit(true);

            verifyDatabase();
            closeStatementsAndConnection();
            verifyNoSideFiles();
            fsync();

            long size = Files.size(target);

            if (size > MAX_PACK_BYTES) {
                throw new PackWriteException("pack exceeds " + MAX_PACK_BYTES + " bytes: " + size);
            }

            return new PackWriteResult(
                    target,
                    PackFiles.sha256(target),
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

    /** finish 전에 닫으면 미완성 파일을 지움. */
    @Override
    public void close() {

        if (finished) {
            return;
        }

        finished = true;
        closeStatementsAndConnection();
        deleteQuietly(target);
    }

    private void insertDocument(PackDocument document) throws SQLException {

        insertDocument.setString(1, document.documentId());
        insertDocument.setString(2, document.url());
        insertDocument.setString(3, document.title());
        insertDocument.setString(
                4,
                document
                        .documentType()
                        .wireValue()
        );
        insertDocument.setString(5, document.exactVersion());
        insertDocument.setString(6, document.sourceSnapshotId());
        insertDocument.setString(7, document.rawSha256());
        insertDocument.setString(8, canonicalJsonColumn(document.sourceMapJson(), "document sourceMapJson"));
        insertDocument.executeUpdate();
    }

    private void insertSections(PackDocument document) throws SQLException {

        Set<String> sectionsSoFar = new HashSet<>();
        int previousOrdinal = -1;

        for (PackSection section : document.sections()) {
            if (section.ordinal() <= previousOrdinal) {
                throw new PackWriteException(
                        "sections must be in ascending ordinal order in document "
                                + document.documentId()
                );
            }

            // 부모는 같은 문서 안에서 먼저 나와야 함(순환·다른 문서 참조 차단).
            if (section.parentSectionId() != null
                    && !sectionsSoFar.contains(section.parentSectionId())) {
                throw new PackWriteException(
                        "parent section "
                                + section.parentSectionId()
                                + " must be declared earlier in the same document"
                );
            }

            insertSection.setString(1, section.sectionId());
            insertSection.setString(2, document.documentId());
            insertSection.setString(3, section.parentSectionId());
            insertSection.setString(4, section.anchor());
            insertSection.setString(5, CanonicalJson.write(section.headingPath()));
            insertSection.setInt(6, section.ordinal());
            insertSection.executeUpdate();

            insertChunks(
                    document,
                    section
            );

            sectionsSoFar.add(section.sectionId());
            previousOrdinal = section.ordinal();
        }
    }

    private void insertChunks(
            PackDocument document,
            PackSection section
    ) throws SQLException {

        int previousOrdinal = -1;

        for (PackChunk chunk : section.chunks()) {
            if (chunk.ordinal() <= previousOrdinal) {
                throw new PackWriteException(
                        "chunks must be in ascending ordinal order in section "
                                + section.sectionId()
                );
            }

            long rowid = nextChunkRowid++;

            insertChunk.setLong(1, rowid);
            insertChunk.setString(2, chunk.chunkId());
            insertChunk.setString(3, document.documentId());
            insertChunk.setString(4, section.sectionId());
            insertChunk.setInt(5, chunk.ordinal());
            insertChunk.setString(6, chunk.content());
            insertChunk.setString(7, chunk.headingPath());
            insertChunk.setString(8, canonicalJsonColumn(chunk.blocksJson(), "chunk blocksJson"));
            insertChunk.setString(9, canonicalJsonColumn(chunk.sourceMapJson(), "chunk sourceMapJson"));
            insertChunk.executeUpdate();

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

            previousOrdinal = chunk.ordinal();
        }
    }

    private void insertVariants(
            PackChunk chunk,
            long rowid
    ) throws SQLException {

        Map<Integer, Integer> defaultsPerBlock = new LinkedHashMap<>();

        for (CodeVariant variant : chunk.codeVariants()) {
            defaultsPerBlock.merge(
                    variant.blockIndex(),
                    variant.isDefault() ? 1 : 0,
                    Integer::sum
            );

            insertVariant.setLong(1, rowid);
            insertVariant.setInt(2, variant.blockIndex());
            insertVariant.setString(
                    3,
                    variant
                            .groupType()
                            .wireValue()
            );
            insertVariant.setString(4, variant.variant());
            insertVariant.setInt(5, variant.ordinal());
            insertVariant.setInt(6, variant.isDefault() ? 1 : 0);
            insertVariant.setString(7, variant.code());
            insertVariant.setString(8, canonicalJsonColumn(variant.sourceMapJson(), "variant sourceMapJson"));
            insertVariant.executeUpdate();
        }

        defaultsPerBlock.forEach((blockIndex, defaults) -> {
            if (defaults != 1) {
                throw new PackWriteException(
                        "code block "
                                + blockIndex
                                + " of chunk "
                                + chunk.chunkId()
                                + " must have exactly one default variant, found "
                                + defaults
                );
            }
        });
    }

    private void insertIdentifiers(
            PackChunk chunk,
            long rowid
    ) throws SQLException {

        for (String identifier : chunk.identifiers()) {
            insertIdentifier.setString(1, identifier);
            insertIdentifier.setLong(2, rowid);
            insertIdentifier.executeUpdate();
        }
    }

    private void insertLinks(
            PackChunk chunk,
            long rowid
    ) throws SQLException {

        for (DocLink link : chunk.docLinks()) {
            insertLink.setLong(1, rowid);
            insertLink.setString(2, link.targetUrl());
            insertLink.setString(3, link.targetAnchor());
            insertLink.executeUpdate();
        }
    }

    private void writeMeta() throws SQLException {

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packId", meta.packId());
        values.put("packType", "DOCUMENT");
        values.put("project", meta.project());
        values.put("docVersion", meta.docVersion());
        values.put("buildId", meta.buildId());
        values.put("packSchemaVersion", 1);
        values.put("minReaderVersion", 1);
        values.put("inputSnapshotHash", meta.inputSnapshotHash());
        values.put("processingConfigHash", meta.processingConfigHash());
        values.put("parserPolicyVersion", meta.parserPolicyVersion());
        values.put("chunkerPolicyVersion", meta.chunkerPolicyVersion());
        values.put("sourceAttribution", meta.sourceAttribution());
        values.put("sourceSnapshotIds", meta.sourceSnapshotIds());
        values.put("capabilities", List.of("BM25", "SECTIONS", "VARIANTS"));
        values.put("embedding", null);
        values.put("createdBy", meta.createdBy());

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            insertMeta(
                    entry.getKey(),
                    CanonicalJson.write(entry.getValue())
            );
        }
    }

    private void insertMeta(
            String key,
            String jsonValue
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pack_meta VALUES(?,?)"
        )) {
            statement.setString(1, key);
            statement.setString(2, jsonValue);
            statement.executeUpdate();
        }
    }

    private void verifyDatabase() throws SQLException {

        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')");
            statement.execute("INSERT INTO chunks_fts(chunks_fts, rank) VALUES('integrity-check', 1)");

            try (ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) {
                    throw new PackWriteException("foreign_key_check failed in table " + rows.getString(1));
                }
            }

            try (ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
                rows.next();

                if (!"ok".equals(rows.getString(1))) {
                    throw new PackWriteException("integrity_check failed: " + rows.getString(1));
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

    private static void requireCount(
            Statement statement,
            String sql,
            long expected
    ) throws SQLException {

        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();

            if (rows.getLong(1) != expected) {
                throw new PackWriteException(sql + " returned " + rows.getLong(1) + ", expected " + expected);
            }
        }
    }

    private void verifyNoSideFiles() {

        for (String suffix : SIDE_FILES) {
            Path side = target.resolveSibling(target.getFileName() + suffix);

            if (Files.exists(side)) {
                throw new PackWriteException("journal side file remains: " + side);
            }
        }
    }

    private void fsync() throws IOException {

        try (FileChannel channel = FileChannel.open(
                target,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            channel.force(true);
        }
    }

    private static String canonicalJsonColumn(
            String json,
            String name
    ) {

        try {
            return CanonicalJson.canonicalize(json);
        } catch (IllegalArgumentException exception) {
            throw new PackWriteException(
                    name + " is not valid canonical JSON input: " + exception.getMessage(),
                    exception
            );
        }
    }

    private static List<String> ddlStatements() throws IOException {

        try (InputStream in = PackWriter.class.getResourceAsStream(DDL_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing resource " + DDL_RESOURCE);
            }

            String ddl = new String(
                    in.readAllBytes(),
                    StandardCharsets.UTF_8
            );

            return Arrays
                    .stream(ddl.split(";\\s*\\n"))
                    .map(String::strip)
                    .filter(sql -> !sql.isEmpty())
                    .toList();
        }
    }

    private PackWriteException rejected(
            String what,
            SQLException exception
    ) {

        return new PackWriteException(
                what + " rejected by pack constraints: " + exception.getMessage(),
                exception
        );
    }

    private void requireOpen() {

        if (finished) {
            throw new PackWriteException("writer is already finished or closed");
        }
    }

    private void closeStatementsAndConnection() {

        for (Statement statement : List.of(
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

    private static void closeQuietly(AutoCloseable closeable) {

        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception ignored) {
            // 정리 단계의 닫기 실패는 원래 오류를 가리지 않게 무시.
        }
    }

    private static void deleteQuietly(Path path) {

        try {
            Files.deleteIfExists(path);

            for (String suffix : SIDE_FILES) {
                Files.deleteIfExists(path.resolveSibling(path.getFileName() + suffix));
            }
        } catch (IOException ignored) {
            // 미완성 파일 정리 실패는 원래 오류를 가리지 않게 무시.
        }
    }
}
