package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.List;

/** Build 시작 또는 재개 결과, pending은 산출물 없는 원본 */
public record BuildState(
        BuildRow build,
        List<SourceEntry> pending,
        List<DocumentHold> holds
) {

    /** 목록 불변 사본 */
    public BuildState {
        pending = List
                .copyOf(pending);
        holds = List
                .copyOf(holds);
    }
}
