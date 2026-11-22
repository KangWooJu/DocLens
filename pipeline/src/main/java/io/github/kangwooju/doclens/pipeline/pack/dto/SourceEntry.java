package io.github.kangwooju.doclens.pipeline.pack.dto;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;

/** Build에 드는 원본 하나의 메타데이터와 원본 object key, bytes는 pipeline이 읽음 */
public record SourceEntry(
        String documentId,
        DocumentType documentType,
        String canonicalUrl,
        String originalUrl,
        String finalUrl,
        String sourceSnapshotId,
        String rawSha256,
        String rawObjectKey
) {
}
