package io.github.kangwooju.doclens.pipeline.pack.dto;

/** snapshot에서 읽은 DONE 산출물 하나, CHUNK JSON과 저장 hash */
public record StoredArtifact(
        String documentId,
        String packDocumentSha256,
        String packDocumentJson
) {
}
