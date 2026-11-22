package io.github.kangwooju.doclens.pipeline.pack.dto;

/** DONE 산출물 하나의 hash와 JSON 바이트 수, 단계 영수증 계산 입력 */
public record DocumentHashes(
        String documentId,
        String structuredSha256,
        String packDocumentSha256,
        long structuredBytes,
        long packDocumentBytes
) {
}
