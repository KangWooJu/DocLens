package io.github.kangwooju.doclens.pipeline.pack.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackInfo;
import io.github.kangwooju.doclens.core.pack.dto.PackMeta;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.reader.PackReader;
import io.github.kangwooju.doclens.core.pack.type.PackMetaKey;
import io.github.kangwooju.doclens.core.pack.writer.PackWriter;
import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;
import io.github.kangwooju.doclens.pipeline.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.pipeline.pack.converter.PackDocumentConverter;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.ExportTarget;
import io.github.kangwooju.doclens.pipeline.pack.dto.PackRecord;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildArtifactMissingException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNotExportableException;
import io.github.kangwooju.doclens.pipeline.pack.helper.BuildDigestHelper;
import io.github.kangwooju.doclens.pipeline.pack.repository.BuildRepository;
import io.github.kangwooju.doclens.pipeline.pack.store.LocalObjectStore;
import lombok.RequiredArgsConstructor;

/**
 * 봉인된 Build의 Pack 작성, 검증, 게시, 업로드, 정리
 * snapshot 트랜잭션은 SQLite 쓰기와 임시 파일 수명과 한 알고리즘이라 write 안에서 열고 finish는 그 밖에서 함
 * 작업 디렉터리는 로컬 저장소, 업로드와 확인은 객체 저장소
 */
@Service
@RequiredArgsConstructor
public class PackExportService {

    /** Build 하나의 최대 청크 수 */
    public static final int MAX_CHUNKS = 100_000;

    private static final Pattern PACK_FILE = Pattern
            .compile("[0-9a-f]{64}\\.pack");

    private final TransactionExecutor transactionExecutor;
    private final BuildRepository buildRepository;
    private final LocalObjectStore localObjectStore;
    private final ObjectStore objectStore;
    private final PackDocumentConverter packDocumentConverter;
    private final BuildDigestHelper buildDigestHelper;

    /** 이미 게시된 유효한 Pack, 검증에 실패한 파일은 건너뛰고 여럿이면 이름이 가장 작은 것, 없으면 null */
    public PackRecord findPublished(ExportTarget target) {

        BuildRow build = target
                .build();

        for (Path file : packFiles(build)) {
            PackRecord record = adoptable(
                    target,
                    file
            );

            if (record != null) {
                return record;
            }
        }

        return null;
    }

    /** snapshot 안에서 CHUNK 산출물을 Pack에 쓰고 영수증과 대조, commit 뒤 Pack 완결 */
    public PackWriteResult write(ExportTarget target) {

        BuildRow build = target
                .build();
        Path directory = directory(build);
        Path temporary = directory
                .resolve("." + UUID
                        .randomUUID() + ".pack.tmp");

        createDirectories(directory);

        try (PackWriter writer = PackWriter
                .create(
                        temporary,
                        meta(target)
                )) {
            transactionExecutor
                    .readSnapshot(() -> {
                        stream(
                                target,
                                writer
                        );

                        return null;
                    });

            return writer
                    .finish();
        }
    }

    /** 완결한 임시 Pack을 검증해 같은 디렉터리의 checksum 이름으로 원자 이동 */
    public PackRecord publish(
            BuildRow build,
            PackWriteResult written
    ) {

        try (PackReader reader = PackReader
                .open(written
                        .path())) {
            reader
                    .info();
        }

        String name = hex(written
                .checksum()) + ".pack";
        Path target = written
                .path()
                .resolveSibling(name);

        try {
            if (Files
                    .exists(target)) {
                Files
                        .delete(written
                                .path());
            } else {
                Files
                        .move(
                                written
                                        .path(),
                                target,
                                StandardCopyOption.ATOMIC_MOVE
                        );
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        syncDirectory(target
                .getParent());

        return new PackRecord(
                objectPrefix(build) + name,
                written
                        .checksum(),
                written
                        .sizeBytes(),
                written
                        .logicalContentSha256()
        );
    }

    /** 기록된 checksum 이름이 아닌 게시 Pack 삭제, 여러 번 불러도 같음 */
    public void prune(BuildRow build) {

        String recorded = hex(build
                .pack()
                .checksum()) + ".pack";

        for (Path file : packFiles(build)) {
            if (!file
                    .getFileName()
                    .toString()
                    .equals(recorded)) {
                try {
                    Files
                            .deleteIfExists(file);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
    }

    /** 로컬에 게시한 Pack을 같은 object key로 객체 저장소에 한 번만 게시 */
    public void upload(PackRecord pack) {

        objectStore
                .publish(
                        pack
                                .objectKey(),
                        localObjectStore
                                .resolve(pack
                                        .objectKey()),
                        pack
                                .checksum()
                );
    }

    /**
     * SUCCEEDED Build가 기록한 객체의 존재와 checksum
     * 객체 저장소에 없고 로컬 Pack이 기록과 같으면 다시 업로드, 둘 다 없으면 BuildArtifactMissingException
     */
    public PackRecord verifyRecorded(BuildRow build) {

        PackRecord pack = build
                .pack();

        if (objectStore
                .contains(
                        pack
                                .objectKey(),
                        pack
                                .checksum()
                )) {
            return pack;
        }

        if (!localObjectStore
                .contains(
                        pack
                                .objectKey(),
                        pack
                                .checksum()
                )) {
            throw new BuildArtifactMissingException(
                    build
                            .buildId()
                            .toString(),
                    pack
                            .objectKey()
            );
        }

        upload(pack);

        return pack;
    }

    // snapshot cursor로 산출물을 Pack에 쓰고 개수와 digest를 CHUNK 영수증과 대조
    private void stream(
            ExportTarget target,
            PackWriter writer
    ) {

        List<List<String>> hashes = new ArrayList<>();
        int[] chunks = {0};

        buildRepository
                .streamArtifacts(
                        target
                                .build()
                                .buildId(),
                        artifact -> {
                            if (!CanonicalJson
                                    .sha256(artifact
                                            .packDocumentJson())
                                    .equals(artifact
                                            .packDocumentSha256())) {
                                throw new IllegalStateException("stored artifact hash mismatch for " + artifact
                                        .documentId());
                            }

                            PackDocument document = packDocumentConverter
                                    .fromJson(artifact
                                            .packDocumentJson());

                            chunks[0] += document
                                    .sections()
                                    .stream()
                                    .mapToInt(section -> section
                                            .chunks()
                                            .size())
                                    .sum();

                            if (chunks[0] > MAX_CHUNKS) {
                                throw new BuildNotExportableException(
                                        target
                                                .build()
                                                .buildId()
                                                .toString(),
                                        "more than " + MAX_CHUNKS + " chunks"
                                );
                            }

                            writer
                                    .add(document);
                            hashes
                                    .add(List
                                            .of(
                                                    artifact
                                                            .documentId(),
                                                    artifact
                                                            .packDocumentSha256()
                                            ));
                        }
                );

        if (hashes
                .size() != target
                        .chunkReceipt()
                        .itemCount() || !buildDigestHelper
                                .stageDigest(hashes)
                                .equals(target
                                        .chunkReceipt()
                                        .digest())) {
            throw new IllegalStateException("exported artifacts differ from the CHUNK receipt");
        }
    }

    // Build 값과 snapshot 목록으로 만든 Pack 메타
    private static PackMeta meta(ExportTarget target) {

        BuildRow build = target
                .build();

        return new PackMeta(
                build
                        .packId(),
                build
                        .project(),
                build
                        .exactVersion(),
                build
                        .buildId()
                        .toString(),
                build
                        .inputSnapshotHash(),
                build
                        .processingConfigHash(),
                build
                        .parserPolicyVersion(),
                build
                        .chunkerPolicyVersion(),
                build
                        .sourceAttribution(),
                target
                        .sourceSnapshotIds(),
                build
                        .createdBy()
        );
    }

    // 게시 후보 파일이 이 Build의 완결 Pack인지 확인한 기록, 아니면 null
    private PackRecord adoptable(
            ExportTarget target,
            Path file
    ) {

        BuildRow build = target
                .build();

        try (PackReader reader = PackReader
                .open(file)) {
            PackInfo info = reader
                    .info();
            String checksum = sha256(file);
            boolean matches = checksum
                    .equals("sha256:" + file
                            .getFileName()
                            .toString()
                            .replace(
                                    ".pack",
                                    ""
                            )) && build
                                    .buildId()
                                    .toString()
                                    .equals(info
                                            .buildId()) && info
                                                    .documentCount() == target
                                                            .chunkReceipt()
                                                            .itemCount() && CanonicalJson
                                                                    .write(build
                                                                            .processingConfigHash())
                                                                    .equals(metaValue(
                                                                            reader,
                                                                            PackMetaKey.PROCESSING_CONFIG_HASH
                                                                    )) && CanonicalJson
                                                                            .write(build
                                                                                    .inputSnapshotHash())
                                                                            .equals(metaValue(
                                                                                    reader,
                                                                                    PackMetaKey.INPUT_SNAPSHOT_HASH
                                                                            ));

            return matches ? new PackRecord(
                    objectPrefix(build) + file
                            .getFileName(),
                    checksum,
                    Files
                            .size(file),
                    info
                            .logicalContentSha256()
            ) : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    // pack_meta 키 하나의 canonical JSON 값
    private static String metaValue(
            PackReader reader,
            PackMetaKey key
    ) {

        try {
            return reader
                    .query(connection -> {
                        try (PreparedStatement statement = connection
                                .prepareStatement("SELECT value FROM pack_meta WHERE key = ?")) {
                            statement
                                    .setString(
                                            1,
                                            key
                                                    .key()
                                    );

                            try (ResultSet resultSet = statement
                                    .executeQuery()) {
                                return resultSet
                                        .next() ? resultSet
                                                .getString(1) : null;
                            }
                        }
                    });
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Build 디렉터리의 게시 Pack 파일, 이름 순
    private List<Path> packFiles(BuildRow build) {

        Path directory = directory(build);

        if (!Files
                .isDirectory(directory)) {
            return List
                    .of();
        }

        try (Stream<Path> files = Files
                .list(directory)) {
            return files
                    .filter(file -> PACK_FILE
                            .matcher(file
                                    .getFileName()
                                    .toString())
                            .matches())
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // Build Pack 디렉터리
    private Path directory(BuildRow build) {

        return localObjectStore
                .resolve(objectPrefix(build));
    }

    // packs/<packId>/<buildId>/ object key 접두어
    private static String objectPrefix(BuildRow build) {

        return "packs/" + build
                .packId() + "/" + build
                        .buildId() + "/";
    }

    // 디렉터리 만들기
    private static void createDirectories(Path directory) {

        try {
            Files
                    .createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // 이동 뒤 디렉터리 fsync 시도, 지원하지 않는 OS는 건너뜀
    private static void syncDirectory(Path directory) {

        try (FileChannel channel = FileChannel
                .open(
                        directory,
                        StandardOpenOption.READ
                )) {
            channel
                    .force(true);
        } catch (IOException exception) {
            // 디렉터리 fsync를 막는 OS는 이름 변경의 내구성을 OS에 맡김
        }
    }

    // sha256: 접두를 뗀 hex
    private static String hex(String checksum) {

        return checksum
                .substring("sha256:"
                        .length());
    }

    // 파일 bytes의 sha256: 접두 hex
    private static String sha256(Path file) {

        try (DigestInputStream digest = new DigestInputStream(
                Files
                        .newInputStream(file),
                MessageDigest
                        .getInstance("SHA-256")
        )) {
            digest
                    .transferTo(OutputStream
                            .nullOutputStream());

            return "sha256:" + HexFormat
                    .of()
                    .formatHex(digest
                            .getMessageDigest()
                            .digest());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
