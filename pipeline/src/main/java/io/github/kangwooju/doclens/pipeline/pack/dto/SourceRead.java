package io.github.kangwooju.doclens.pipeline.pack.dto;

import io.github.kangwooju.doclens.pipeline.pack.type.SourceReadOutcome;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;

/** 원본 읽기 결과와 READ일 때의 원본 문서 */
public record SourceRead(
        SourceReadOutcome outcome,
        SourceDocument document
) {
}
