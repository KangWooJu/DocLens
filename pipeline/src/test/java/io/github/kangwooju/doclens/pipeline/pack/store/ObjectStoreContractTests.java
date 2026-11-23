package io.github.kangwooju.doclens.pipeline.pack.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;

/** 로컬과 S3 저장소가 함께 지키는 읽기와 한 번만 게시 계약 */
abstract class ObjectStoreContractTests {

    @TempDir
    Path files;

    // 시험 대상 저장소
    abstract ObjectStore store();

    @Test
    @DisplayName("없는 key는 빈 값, contains는 false")
    void missingKeyIsEmpty() {

        String key = key();

        assertTrue(store()
                .read(key)
                .isEmpty());
        assertFalse(store()
                .contains(
                        key,
                        checksum("absent")
                ));
    }

    @Test
    @DisplayName("publish 뒤 contains 참, read는 같은 bytes")
    void publishesOnce() throws IOException {

        String key = key();

        store()
                .publish(
                        key,
                        file("first"),
                        checksum("first")
                );

        assertTrue(store()
                .contains(
                        key,
                        checksum("first")
                ));
        assertFalse(store()
                .contains(
                        key,
                        checksum("second")
                ));
        assertArrayEquals(
                bytes("first"),
                store()
                        .read(key)
                        .orElseThrow()
        );
    }

    @Test
    @DisplayName("같은 checksum 재게시는 그대로 채택")
    void adoptsSameChecksum() throws IOException {

        String key = key();

        store()
                .publish(
                        key,
                        file("first"),
                        checksum("first")
                );
        store()
                .publish(
                        key,
                        file("first"),
                        checksum("first")
                );

        assertTrue(store()
                .contains(
                        key,
                        checksum("first")
                ));
    }

    @Test
    @DisplayName("같은 key에 다른 checksum 게시는 IllegalStateException, 처음 bytes 유지")
    void rejectsDifferentChecksum() throws IOException {

        String key = key();
        Path second = file("second");

        store()
                .publish(
                        key,
                        file("first"),
                        checksum("first")
                );

        assertThrows(
                IllegalStateException.class,
                () -> store()
                        .publish(
                                key,
                                second,
                                checksum("second")
                        )
        );
        assertArrayEquals(
                bytes("first"),
                store()
                        .read(key)
                        .orElseThrow()
        );
    }

    // 시험마다 다른 Pack key
    private static String key() {

        return "packs/contract/" + UUID
                .randomUUID() + "/object.pack";
    }

    // 내용을 담은 게시 대상 파일
    private Path file(String content) throws IOException {

        return Files
                .write(
                        files
                                .resolve(UUID
                                        .randomUUID() + ".bin"),
                        bytes(content)
                );
    }

    // 내용의 UTF-8 bytes
    private static byte[] bytes(String content) {

        return content
                .getBytes(StandardCharsets.UTF_8);
    }

    // 내용 bytes의 sha256: 접두 hex
    private static String checksum(String content) {

        try {
            return "sha256:" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
