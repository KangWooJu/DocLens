package io.github.kangwooju.doclens.pipeline.pack.store;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;

/** object key를 로컬 디렉터리 아래 파일로 다루는 저장소, Pack 작업 디렉터리와 기본 객체 저장소 */
@Component
public class LocalObjectStore implements ObjectStore {

    private final Path root;

    /** object key의 기준 디렉터리 */
    public LocalObjectStore(@Value("${doclens.pipeline.object-root}") Path root) {

        this.root = root
                .toAbsolutePath()
                .normalize();
    }

    /** object key의 bytes, 파일이 없으면 빈 값 */
    @Override
    public Optional<byte[]> read(String objectKey) {

        try {
            return Optional
                    .of(Files
                            .readAllBytes(resolve(objectKey)));
        } catch (NoSuchFileException exception) {
            return Optional
                    .empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 이미 있는 파일은 checksum만 확인, 없으면 같은 디렉터리 임시 파일에 복사해 검증 후 원자 이동 */
    @Override
    public void publish(
            String objectKey,
            Path file,
            String checksum
    ) {

        Path target = resolve(objectKey);

        if (Files
                .exists(target)) {
            requireChecksum(
                    objectKey,
                    target,
                    checksum
            );

            return;
        }

        Path temporary = target
                .resolveSibling("." + UUID
                        .randomUUID() + ".tmp");

        try {
            Files
                    .createDirectories(target
                            .getParent());
            Files
                    .copy(
                            file,
                            temporary
                    );
            requireChecksum(
                    objectKey,
                    temporary,
                    checksum
            );
            Files
                    .move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE
                    );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            deleteIfExists(temporary);
        }
    }

    /** object key의 파일이 있고 bytes의 checksum이 같으면 true */
    @Override
    public boolean contains(
            String objectKey,
            String checksum
    ) {

        Path path = resolve(objectKey);

        return Files
                .isRegularFile(path) && sha256(path)
                        .equals(checksum);
    }

    /** object key의 파일 경로, 기준 디렉터리 밖이면 IllegalArgumentException */
    public Path resolve(String objectKey) {

        Path path = root
                .resolve(objectKey)
                .normalize();

        if (!path
                .startsWith(root)) {
            throw new IllegalArgumentException("object key escapes the object root: " + objectKey);
        }

        return path;
    }

    // 파일 checksum이 다르면 IllegalStateException
    private static void requireChecksum(
            String objectKey,
            Path file,
            String checksum
    ) {

        if (!sha256(file)
                .equals(checksum)) {
            throw new IllegalStateException("object bytes differ from checksum " + checksum + " at " + objectKey);
        }
    }

    // 남은 임시 파일 삭제
    private static void deleteIfExists(Path file) {

        try {
            Files
                    .deleteIfExists(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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
