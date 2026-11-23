package io.github.kangwooju.doclens.pipeline.pack.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.kangwooju.doclens.testsupport.S3MockTestConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 로컬 S3 호환 서버의 적합성, 조건부 쓰기와 틀린 checksum 거부는 결과를 출력만 */
@Testcontainers
@DisplayName("S3Mock 적합성")
class S3ConformanceTests {

    @Container
    static final GenericContainer<?> S3_MOCK = S3MockTestConfiguration
            .container();

    private static S3Client client;

    // 시험 클라이언트 열기
    @BeforeAll
    static void open() {

        client = S3MockTestConfiguration
                .client(S3_MOCK);
    }

    // 시험 클라이언트 정리
    @AfterAll
    static void close() {

        client
                .close();
    }

    @Test
    @DisplayName("없는 key GET은 404 NoSuchKey")
    void missingKeyIsNotFound() {

        assertThrows(
                NoSuchKeyException.class,
                () -> client
                        .getObjectAsBytes(GetObjectRequest
                                .builder()
                                .bucket(S3MockTestConfiguration.BUCKET)
                                .key(key())
                                .build())
        );
    }

    @Test
    @DisplayName("If-None-Match PUT 두 번의 두 번째 응답 기록")
    void recordsConditionalPut() {

        String key = key();
        byte[] bytes = "conditional"
                .getBytes();

        put(
                key,
                bytes,
                true,
                sha256(bytes)
        );

        String outcome;

        try {
            put(
                    key,
                    bytes,
                    true,
                    sha256(bytes)
            );
            outcome = "accepted";
        } catch (S3Exception exception) {
            outcome = "status " + exception
                    .statusCode();
        }

        System.out
                .println("conformance if-none-match second put: " + outcome);
    }

    @Test
    @DisplayName("ChecksumSHA256 PUT 성공, HEAD checksum 되읽기 결과 기록")
    void readsBackChecksum() {

        String key = key();
        byte[] bytes = "checksum"
                .getBytes();

        put(
                key,
                bytes,
                false,
                sha256(bytes)
        );

        String stored = client
                .headObject(HeadObjectRequest
                        .builder()
                        .bucket(S3MockTestConfiguration.BUCKET)
                        .key(key)
                        .checksumMode(ChecksumMode.ENABLED)
                        .build())
                .checksumSHA256();

        System.out
                .println("conformance head checksum: " + (sha256(bytes)
                        .equals(stored) ? "same" : "different " + stored));
    }

    @Test
    @DisplayName("틀린 ChecksumSHA256 PUT 거부 여부 기록")
    void recordsWrongChecksum() {

        String outcome;

        try {
            put(
                    key(),
                    "body"
                            .getBytes(),
                    false,
                    sha256("other"
                            .getBytes())
            );
            outcome = "accepted";
        } catch (S3Exception exception) {
            outcome = "status " + exception
                    .statusCode();
        }

        System.out
                .println("conformance wrong checksum put: " + outcome);
    }

    @Test
    @DisplayName("1MiB 단일 PUT을 같은 bytes로 되읽음")
    void putsOneMebibyte() {

        String key = key();
        byte[] bytes = new byte[1 << 20];

        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }

        put(
                key,
                bytes,
                false,
                sha256(bytes)
        );

        assertArrayEquals(
                bytes,
                client
                        .getObjectAsBytes(GetObjectRequest
                                .builder()
                                .bucket(S3MockTestConfiguration.BUCKET)
                                .key(key)
                                .build())
                        .asByteArray()
        );
    }

    // 시험 bucket에 bytes PUT, 조건부면 If-None-Match
    private static void put(
            String key,
            byte[] bytes,
            boolean conditional,
            String checksum
    ) {

        PutObjectRequest.Builder request = PutObjectRequest
                .builder()
                .bucket(S3MockTestConfiguration.BUCKET)
                .key(key)
                .checksumSHA256(checksum);

        if (conditional) {
            request
                    .ifNoneMatch("*");
        }

        client
                .putObject(
                        request
                                .build(),
                        RequestBody
                                .fromBytes(bytes)
                );
    }

    // 시험마다 다른 key
    private static String key() {

        return "packs/conformance/" + UUID
                .randomUUID() + ".bin";
    }

    // bytes의 base64 SHA-256
    private static String sha256(byte[] bytes) {

        try {
            return Base64
                    .getEncoder()
                    .encodeToString(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
