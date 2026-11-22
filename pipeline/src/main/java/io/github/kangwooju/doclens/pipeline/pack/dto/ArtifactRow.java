package io.github.kangwooju.doclens.pipeline.pack.dto;

import io.github.kangwooju.doclens.pipeline.pack.type.ArtifactOutcome;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

/** 문서 하나의 산출물 행, DONE은 JSON 두 개와 hash, HELD는 이유만 */
public record ArtifactRow(
        ArtifactOutcome outcome,
        HoldReason holdReason,
        String structuredJson,
        String structuredSha256,
        String packDocumentJson,
        String packDocumentSha256
) {
}
