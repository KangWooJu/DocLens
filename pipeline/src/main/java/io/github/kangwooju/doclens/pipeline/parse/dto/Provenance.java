package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** 문서 단위 출처, rawSha256은 기획의 rawArtifactHash와 같은 값, includes는 문서가 실제로 쓴 include이고 HTML 원본은 빈 목록 */
public record Provenance(
        String rawSha256,
        String sourceSnapshotId,
        String originalUrl,
        String finalUrl,
        String profileId,
        String profileVersion,
        String parserPolicyVersion,
        List<ResolvedInclude> includes
) {

    /** 목록 불변 사본 */
    public Provenance {
        includes = List
                .copyOf(includes);
    }
}
