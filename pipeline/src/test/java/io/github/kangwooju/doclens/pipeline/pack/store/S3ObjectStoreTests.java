package io.github.kangwooju.doclens.pipeline.pack.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;
import io.github.kangwooju.doclens.testsupport.S3MockTestConfiguration;

@Testcontainers
@DisplayName("S3 객체 저장소 계약")
class S3ObjectStoreTests extends ObjectStoreContractTests {

    @Container
    static final GenericContainer<?> S3_MOCK = S3MockTestConfiguration
            .container();

    private S3ObjectStore store;

    // S3Mock 시험 bucket에 붙는 저장소
    @BeforeEach
    void open() {

        store = new S3ObjectStore(
                S3MockTestConfiguration
                        .endpoint(S3_MOCK),
                S3MockTestConfiguration.BUCKET,
                "us-east-1",
                S3MockTestConfiguration.CREDENTIAL,
                S3MockTestConfiguration.CREDENTIAL
        );
    }

    // 클라이언트 정리
    @AfterEach
    void close() {

        store
                .close();
    }

    // 시험 bucket 저장소
    @Override
    ObjectStore store() {

        return store;
    }
}
