package io.github.kangwooju.doclens.pipeline.pack.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.kangwooju.doclens.testsupport.S3MockTestConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
@DisplayName("S3 객체 저장소 실패는 없음으로 바꾸지 않음")
class S3ObjectStoreFailureTests {

    @Container
    static final GenericContainer<?> S3_MOCK = S3MockTestConfiguration
            .container();

    @TempDir
    Path files;

    @Test
    @DisplayName("없는 bucket의 404는 빈 값이 아니라 S3Exception")
    void missingBucketThrows() {

        try (S3ObjectStore store = store(
                S3MockTestConfiguration
                        .endpoint(S3_MOCK),
                "missing-bucket"
        )) {
            S3Exception exception = assertThrows(
                    S3Exception.class,
                    () -> store
                            .read("raw/spring-boot/4.1.2/a.html")
            );

            assertEquals(
                    404,
                    exception
                            .statusCode()
            );
        }
    }

    @Test
    @DisplayName("닫힌 포트 endpoint는 빈 값이 아니라 SdkClientException")
    void unreachableEndpointThrows() throws IOException {

        int port;

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket
                    .getLocalPort();
        }

        try (S3ObjectStore store = store(
                URI
                        .create("http://127.0.0.1:" + port),
                S3MockTestConfiguration.BUCKET
        )) {
            assertThrows(
                    SdkClientException.class,
                    () -> store
                            .read("raw/spring-boot/4.1.2/a.html")
            );
        }
    }

    @Test
    @DisplayName("packs/ 밖 key 게시는 IllegalArgumentException")
    void rejectsKeysOutsidePacks() throws IOException {

        Path file = Files
                .writeString(
                        files
                                .resolve("raw.html"),
                        "raw"
                );

        try (S3ObjectStore store = store(
                S3MockTestConfiguration
                        .endpoint(S3_MOCK),
                S3MockTestConfiguration.BUCKET
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store
                            .publish(
                                    "raw/spring-boot/4.1.2/raw.html",
                                    file,
                                    "sha256:" + "0"
                                            .repeat(64)
                            )
            );
        }
    }

    // endpoint와 bucket의 저장소
    private static S3ObjectStore store(
            URI endpoint,
            String bucket
    ) {

        return new S3ObjectStore(
                endpoint,
                bucket,
                "us-east-1",
                S3MockTestConfiguration.CREDENTIAL,
                S3MockTestConfiguration.CREDENTIAL
        );
    }
}
