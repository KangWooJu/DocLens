package io.github.kangwooju.doclens.pipeline.pack.dto;

/** 처리 설정 hash와 그 preimage canonical JSON, Build에 함께 저장하는 정책 버전 */
public record ProcessingConfig(
        String hash,
        String preimageJson,
        String parserPolicyVersion,
        String chunkerPolicyVersion
) {
}
