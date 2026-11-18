package io.github.kangwooju.doclens.core.pack;

import java.nio.file.Path;

/** 완결된 Pack 파일 정보, checksum은 파일 전체 SHA-256(`sha256:` 접두어) */
public record PackWriteResult(
        Path path,
        String checksum,
        long sizeBytes,
        String logicalContentSha256
) {
}
