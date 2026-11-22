package io.github.kangwooju.doclens.pipeline.pack.dto;

import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

/** 보류된 문서와 이유 */
public record DocumentHold(
        String documentId,
        HoldReason reason
) {
}
