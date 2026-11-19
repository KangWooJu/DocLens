package io.github.kangwooju.doclens.core.pack.dto;

import java.nio.file.Path;

/** 완성된 Pack 파일 정보, checksum은 파일 전체 SHA-256 */
public record PackWriteResult(
        Path path,
        String checksum,
        long sizeBytes,
        String logicalContentSha256
) {
}
