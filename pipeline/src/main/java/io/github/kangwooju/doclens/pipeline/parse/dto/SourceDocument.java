package io.github.kangwooju.doclens.pipeline.parse.dto;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;

/** 수집기가 넘긴 원본 문서 하나와 메타데이터, rawSha256은 원본 바이트 기준 */
public record SourceDocument(
        String documentId,
        String project,
        String exactVersion,
        DocumentType documentType,
        String canonicalUrl,
        String originalUrl,
        String finalUrl,
        String sourceSnapshotId,
        String rawSha256,
        byte[] rawBytes
) {

    /** 원본 바이트 방어 복사 */
    public SourceDocument {
        rawBytes = rawBytes
                .clone();
    }
}
