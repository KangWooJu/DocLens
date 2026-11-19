package io.github.kangwooju.doclens.pipeline.parse.dto;

/** 문서 단위 출처, rawSha256은 기획의 rawArtifactHash와 같은 값 */
public record Provenance(
        String rawSha256,
        String sourceSnapshotId,
        String originalUrl,
        String finalUrl,
        String profileId,
        String profileVersion,
        String parserPolicyVersion
) {
}
