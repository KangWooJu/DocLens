package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/**
 * 문서 단위 출처, rawSha256은 기획의 rawArtifactHash와 같은 값
 * includes는 문서가 실제로 쓴 include, titleSources는 빈 글 xref에 제목을 준 같은 Build의 다른 쪽, HTML 원본은 둘 다 빈 목록
 */
public record Provenance(
        String rawSha256,
        String sourceSnapshotId,
        String originalUrl,
        String finalUrl,
        String profileId,
        String profileVersion,
        String parserPolicyVersion,
        List<ResolvedInclude> includes,
        List<TitleSource> titleSources
) {

    /** 목록 불변 사본 */
    public Provenance {
        includes = List
                .copyOf(includes);
        titleSources = List
                .copyOf(titleSources);
    }
}
