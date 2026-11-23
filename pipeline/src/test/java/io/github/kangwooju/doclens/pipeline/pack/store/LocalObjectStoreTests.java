package io.github.kangwooju.doclens.pipeline.pack.store;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;

@DisplayName("로컬 디렉터리 객체 저장소 계약")
class LocalObjectStoreTests extends ObjectStoreContractTests {

    @TempDir
    Path root;

    // 시험 디렉터리 기준 로컬 저장소
    @Override
    ObjectStore store() {

        return new LocalObjectStore(root);
    }
}
