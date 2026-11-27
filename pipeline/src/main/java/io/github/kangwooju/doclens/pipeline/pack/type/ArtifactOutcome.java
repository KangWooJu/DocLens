package io.github.kangwooju.doclens.pipeline.pack.type;

/** 문서 하나의 산출물 결과, DONE은 PARSE와 CHUNK 산출물 있음, HELD는 보류 이유만 */
public enum ArtifactOutcome {
    DONE,
    HELD
}
