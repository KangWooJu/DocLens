package io.github.kangwooju.doclens.pipeline.pack.store;

import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** S3 호환 저장소 adapter, 404만 없음으로 보고 권한 거부와 timeout은 예외로 전파 */
@Component
@Primary
@ConditionalOnProperty(
        name = "doclens.pipeline.object-store",
        havingValue = "s3"
)
public class S3ObjectStore implements ObjectStore, AutoCloseable {

    /** pipeline이 게시할 수 있는 유일한 key 접두어 */
    public static final String PUBLISH_PREFIX = "packs/";

    private static final String PREFIX = "sha256:";

    private final S3Client s3Client;
    private final String bucket;

    /** endpoint, bucket, region, 정적 자격 증명의 path-style 클라이언트, checksum은 요청이 요구할 때만 */
    public S3ObjectStore(
            @Value("${doclens.pipeline.s3.endpoint}") URI endpoint,
            @Value("${doclens.pipeline.s3.bucket}") String bucket,
            @Value("${doclens.pipeline.s3.region:us-east-1}") String region,
            @Value("${doclens.pipeline.s3.access-key}") String accessKey,
            @Value("${doclens.pipeline.s3.secret-key}") String secretKey
    ) {

        this.bucket = bucket;
        this.s3Client = S3Client
                .builder()
                .endpointOverride(endpoint)
                .region(Region
                        .of(region))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider
                        .create(AwsBasicCredentials
                                .create(
                                        accessKey,
                                        secretKey
                                )))
                .build();
    }

    /** object key의 bytes 전체, 응답 checksum이 있으면 SDK가 끝까지 읽으며 검증, 404만 빈 값 */
    @Override
    public Optional<byte[]> read(String objectKey) {

        try {
            return Optional
                    .of(s3Client
                            .getObjectAsBytes(GetObjectRequest
                                    .builder()
                                    .bucket(bucket)
                                    .key(objectKey)
                                    .checksumMode(ChecksumMode.ENABLED)
                                    .build())
                            .asByteArray());
        } catch (NoSuchKeyException exception) {
            return Optional
                    .empty();
        }
    }

    /** packs/ key만, 없을 때 If-None-Match 조건과 SHA-256 checksum으로 PUT, 이미 있거나 412, 409면 checksum 비교 */
    @Override
    public void publish(
            String objectKey,
            Path file,
            String checksum
    ) {

        if (!objectKey
                .startsWith(PUBLISH_PREFIX)) {
            throw new IllegalArgumentException("pipeline publishes only " + PUBLISH_PREFIX + " keys: " + objectKey);
        }

        if (storedChecksum(objectKey)
                .isPresent()) {
            requireChecksum(
                    objectKey,
                    checksum
            );

            return;
        }

        try {
            s3Client
                    .putObject(
                            PutObjectRequest
                                    .builder()
                                    .bucket(bucket)
                                    .key(objectKey)
                                    .ifNoneMatch("*")
                                    .checksumSHA256(Base64
                                            .getEncoder()
                                            .encodeToString(HexFormat
                                                    .of()
                                                    .parseHex(checksum
                                                            .substring(PREFIX
                                                                    .length()))))
                                    .build(),
                            RequestBody
                                    .fromFile(file)
                    );
        } catch (S3Exception exception) {
            if (exception
                    .statusCode() != 412 && exception
                            .statusCode() != 409) {
                throw exception;
            }

            requireChecksum(
                    objectKey,
                    checksum
            );
        }
    }

    /** object key가 있고 저장된 SHA-256이 같으면 true, 404면 false */
    @Override
    public boolean contains(
            String objectKey,
            String checksum
    ) {

        return storedChecksum(objectKey)
                .filter(checksum::equals)
                .isPresent();
    }

    /** 클라이언트 연결 정리 */
    @Override
    public void close() {

        s3Client
                .close();
    }

    // 저장된 checksum이 다르거나 없으면 IllegalStateException
    private void requireChecksum(
            String objectKey,
            String checksum
    ) {

        if (!contains(
                objectKey,
                checksum
        )) {
            throw new IllegalStateException("object differs from checksum " + checksum + " at " + objectKey);
        }
    }

    // HEAD의 SHA-256 checksum, 없으면 본문을 읽어 직접 계산, 404면 빈 값
    private Optional<String> storedChecksum(String objectKey) {

        String checksum;

        try {
            checksum = s3Client
                    .headObject(HeadObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .checksumMode(ChecksumMode.ENABLED)
                            .build())
                    .checksumSHA256();
        } catch (NoSuchKeyException exception) {
            return Optional
                    .empty();
        }

        if (checksum != null) {
            return Optional
                    .of(PREFIX + HexFormat
                            .of()
                            .formatHex(Base64
                                    .getDecoder()
                                    .decode(checksum)));
        }

        return read(objectKey)
                .map(S3ObjectStore::sha256);
    }

    // bytes의 sha256: 접두 hex
    private static String sha256(byte[] bytes) {

        try {
            return PREFIX + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
