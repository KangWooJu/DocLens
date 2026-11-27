package io.github.kangwooju.doclens.pipeline.pack.dto;

/** 단계 영수증 값, digest와 항목 수와 바이트 수 */
public record StageDigest(
        String digest,
        int itemCount,
        long byteCount
) {
}
