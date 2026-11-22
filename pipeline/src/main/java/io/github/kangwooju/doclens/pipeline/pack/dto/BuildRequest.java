package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.List;

/** project와 정확 버전 하나의 Build 요청, 같은 요청 재전송은 재개 */
public record BuildRequest(
        String project,
        String exactVersion,
        String applicability,
        String inputScopeKey,
        String runInputSnapshotHash,
        String processingConfigHash,
        String modelChecksum,
        List<SourceEntry> sources
) {

    /** 원본 목록 불변 사본, null은 Policy가 입력 오류로 거절 */
    public BuildRequest {
        sources = sources == null ? null : List
                .copyOf(sources);
    }
}
