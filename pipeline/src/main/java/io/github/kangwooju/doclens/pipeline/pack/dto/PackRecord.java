package io.github.kangwooju.doclens.pipeline.pack.dto;

/** 게시한 Pack의 object key, 파일 checksum, 크기, 논리 내용 hash */
public record PackRecord(
        String objectKey,
        String checksum,
        long sizeBytes,
        String logicalContentSha256
) {
}
