package io.github.kangwooju.doclens.pipeline.pack.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.pipeline.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.ExportTarget;
import io.github.kangwooju.doclens.pipeline.pack.dto.PackRecord;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildArtifactMissingException;
import io.github.kangwooju.doclens.pipeline.pack.service.BuildService;
import io.github.kangwooju.doclens.pipeline.pack.service.PackExportService;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.testsupport.PackRequestFixtures;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;
import io.github.kangwooju.doclens.testsupport.S3MockTestConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@SpringBootTest(properties = "doclens.pipeline.object-store=s3")
@Import({
        PostgresTestConfiguration.class,
        S3MockTestConfiguration.class
})
@DisplayName("S3 객체 저장소로 원본 읽기와 Pack 업로드")
class ExportS3Tests {

    @TempDir
    static Path objectRoot;

    @Autowired
    private PackBuildFacade facade;

    @Autowired
    private BuildService buildService;

    @Autowired
    private PackExportService packExportService;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private S3Client s3Client;

    // 시험 Pack 작업 디렉터리 지정
    @DynamicPropertySource
    static void objectRoot(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.pipeline.object-root",
                        () -> objectRoot
                                .toString()
                );
    }

    // 시험마다 표와 로컬 파일 비우고 원본 두 개를 S3에만 올림
    @BeforeEach
    void prepare() throws IOException {

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();

        try (Stream<Path> files = Files
                .walk(objectRoot)) {
            for (Path path : files
                    .sorted((left, right) -> right
                            .compareTo(left))
                    .toList()) {
                if (!path
                        .equals(objectRoot)) {
                    Files
                            .delete(path);
                }
            }
        }

        for (SourceEntry source : PackRequestFixtures
                .sources()) {
            s3Client
                    .putObject(
                            PutObjectRequest
                                    .builder()
                                    .bucket(S3MockTestConfiguration.BUCKET)
                                    .key(source
                                            .rawObjectKey())
                                    .build(),
                            RequestBody
                                    .fromBytes(PackRequestFixtures
                                            .bytes(PackRequestFixtures
                                                    .fixtureOf(source)))
                    );
        }
    }

    @Test
    @DisplayName("S3 원본으로 build하면 SUCCEEDED, 같은 key와 checksum의 Pack이 S3에 있음")
    void uploadsPack() {

        BuildResult result = facade
                .build(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()));

        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertEquals(
                result
                        .pack()
                        .checksum(),
                s3Checksum(result
                        .pack()
                        .objectKey())
        );
    }

    @Test
    @DisplayName("S3 Pack만 지운 뒤 export는 로컬 Pack으로 다시 업로드하고 성공")
    void reuploadsFromLocalPack() {

        BuildResult result = facade
                .build(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()));

        deleteS3(result
                .pack()
                .objectKey());

        BuildResult exported = facade
                .export(result
                        .buildId());

        assertEquals(
                BuildStatus.SUCCEEDED,
                exported
                        .status()
        );
        assertEquals(
                result
                        .pack()
                        .checksum(),
                s3Checksum(result
                        .pack()
                        .objectKey())
        );
    }

    @Test
    @DisplayName("로컬과 S3 Pack을 모두 지우면 BuildArtifactMissingException")
    void reportsMissingPack() throws IOException {

        BuildResult result = facade
                .build(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()));

        deleteS3(result
                .pack()
                .objectKey());
        Files
                .delete(objectRoot
                        .resolve(result
                                .pack()
                                .objectKey()));

        assertThrows(
                BuildArtifactMissingException.class,
                () -> facade
                        .export(result
                                .buildId())
        );
    }

    @Test
    @DisplayName("업로드 뒤 SUCCEEDED 기록 전에 멈춘 Build는 다음 export가 같은 Pack을 채택")
    void adoptsUploadedPack() {

        UUID buildId = facade
                .ingest(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()))
                .buildId();
        ExportTarget target = transactionExecutor
                .write(() -> buildService
                        .exportTarget(buildId));
        PackRecord uploaded = packExportService
                .publish(
                        target
                                .build(),
                        packExportService
                                .write(target)
                );

        packExportService
                .upload(uploaded);

        BuildResult result = facade
                .export(buildId);

        assertEquals(
                BuildStatus.SUCCEEDED,
                result
                        .status()
        );
        assertEquals(
                uploaded,
                result
                        .pack()
        );
        assertEquals(
                1,
                s3Client
                        .listObjectsV2(ListObjectsV2Request
                                .builder()
                                .bucket(S3MockTestConfiguration.BUCKET)
                                .prefix("packs/spring-boot-4.1.2/" + buildId + "/")
                                .build())
                        .keyCount()
        );
    }

    // S3 객체 bytes의 sha256: 접두 hex
    private String s3Checksum(String objectKey) {

        byte[] bytes = s3Client
                .getObjectAsBytes(GetObjectRequest
                        .builder()
                        .bucket(S3MockTestConfiguration.BUCKET)
                        .key(objectKey)
                        .build())
                .asByteArray();

        try {
            return "sha256:" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // S3 객체 삭제
    private void deleteS3(String objectKey) {

        s3Client
                .deleteObject(DeleteObjectRequest
                        .builder()
                        .bucket(S3MockTestConfiguration.BUCKET)
                        .key(objectKey)
                        .build());
    }
}
