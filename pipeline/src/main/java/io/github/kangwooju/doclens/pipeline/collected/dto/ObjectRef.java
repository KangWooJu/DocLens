package io.github.kangwooju.doclens.pipeline.collected.dto;

/** contracts objectRef, object key와 sha256 접두 hash와 크기 */
public record ObjectRef(
        String objectKey,
        String contentHash,
        long sizeBytes
) {
}
