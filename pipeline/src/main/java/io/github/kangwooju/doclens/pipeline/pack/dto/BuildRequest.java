package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.List;

/** project와 정확 버전 하나의 Build 요청, includes는 문서가 아니라 변환 때 읽는 같은 run의 원본, 같은 요청 재전송은 재개 */
public record BuildRequest(
        String project,
        String exactVersion,
        String applicability,
        String inputScopeKey,
        String runInputSnapshotHash,
        String processingConfigHash,
        String modelChecksum,
        List<SourceEntry> sources,
        List<SourceEntry> includes
) {

    /** 원본과 include 목록 불변 사본, null은 Policy가 입력 오류로 거절 */
    public BuildRequest {
        sources = sources == null ? null : List
                .copyOf(sources);
        includes = includes == null ? null : List
                .copyOf(includes);
    }
}
