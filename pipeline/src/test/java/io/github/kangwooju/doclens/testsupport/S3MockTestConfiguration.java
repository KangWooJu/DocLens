package io.github.kangwooju.doclens.testsupport;

import java.net.URI;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 시험용 S3Mock 컨테이너와 S3 연결 속성
 * object-store를 s3로 바꾸는 시험만 가져다 씀
 */
@TestConfiguration(proxyBeanMethods = false)
public class S3MockTestConfiguration {

    /** 컨테이너가 시작할 때 만드는 시험 bucket */
    public static final String BUCKET = "doclens";

    /** S3Mock 5.2.3 이미지, 다중 아키텍처 digest 고정 */
    public static final String IMAGE = "adobe/s3mock:5.2.3@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92";

    /** 로컬 전용 자격 증명 값 */
    public static final String CREDENTIAL = "local";

    private static final int PORT = 9090;

    /** 시험 bucket이 미리 있는 S3Mock 컨테이너, 시작 전 */
    public static GenericContainer<?> container() {

        return new GenericContainer<>(IMAGE)
                .withExposedPorts(PORT)
                .withEnv(
                        "COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS",
                        BUCKET
                );
    }

    /** 시작한 컨테이너의 HTTP endpoint */
    public static URI endpoint(GenericContainer<?> container) {

        return URI
                .create("http://" + container
                        .getHost() + ":" + container
                                .getMappedPort(PORT));
    }

    /** 시작한 컨테이너에 붙는 path-style 시험 클라이언트 */
    public static S3Client client(GenericContainer<?> container) {

        return S3Client
                .builder()
                .endpointOverride(endpoint(container))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider
                        .create(AwsBasicCredentials
                                .create(
                                        CREDENTIAL,
                                        CREDENTIAL
                                )))
                .build();
    }

    // 시험 컨텍스트가 공유하는 S3Mock 컨테이너
    @Bean
    GenericContainer<?> s3Mock() {

        return container();
    }

    // 컨테이너 endpoint, bucket, 자격 증명 속성
    @Bean
    DynamicPropertyRegistrar s3MockProperties(GenericContainer<?> s3Mock) {

        return registry -> {
            registry
                    .add(
                            "doclens.pipeline.s3.endpoint",
                            () -> endpoint(s3Mock)
                                    .toString()
                    );
            registry
                    .add(
                            "doclens.pipeline.s3.bucket",
                            () -> BUCKET
                    );
            registry
                    .add(
                            "doclens.pipeline.s3.access-key",
                            () -> CREDENTIAL
                    );
            registry
                    .add(
                            "doclens.pipeline.s3.secret-key",
                            () -> CREDENTIAL
                    );
        };
    }

    // 시험이 원본을 올리고 객체를 지우는 클라이언트
    @Bean
    S3Client testS3Client(GenericContainer<?> s3Mock) {

        return client(s3Mock);
    }
}
