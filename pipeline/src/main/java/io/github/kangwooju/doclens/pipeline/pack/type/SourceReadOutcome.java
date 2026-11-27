package io.github.kangwooju.doclens.pipeline.pack.type;

/** 원본 읽기 결과, 파일 없음은 재전송으로 이어 가고 hash 불일치는 Build 실패 */
public enum SourceReadOutcome {
    READ,
    MISSING,
    HASH_MISMATCH
}
