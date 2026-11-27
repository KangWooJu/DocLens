package io.github.kangwooju.doclens.mcp.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.kangwooju.doclens.core.pack.dto.PackMeta;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.fixture.TestPacks;

/**
 * 시험용 Pack을 캐시 설치 경로 `packs/<packId>/<buildId>/<sha256>.pack`에 놓는 도우미
 */
public final class PackInstalls {

    // 정적 도우미 전용
    private PackInstalls() {
    }

    /** builder의 Pack 생성과 cacheRoot 아래 설치, 설치 파일 경로 반환 */
    public static Path install(
            Path cacheRoot,
            TestPacks.Builder builder
    ) {

        try {
            Path work = Files
                    .createTempDirectory("doclens-pack");
            PackWriteResult written = builder
                    .writeTo(work);
            PackMeta meta = builder
                    .meta();
            Path target = cacheRoot
                    .resolve("packs")
                    .resolve(meta
                            .packId())
                    .resolve(meta
                            .buildId())
                    .resolve(written
                            .checksum()
                            .substring("sha256:"
                                    .length()) + ".pack");
            Files
                    .createDirectories(target
                            .getParent());

            return Files
                    .move(
                            written
                                    .path(),
                            target
                    );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
