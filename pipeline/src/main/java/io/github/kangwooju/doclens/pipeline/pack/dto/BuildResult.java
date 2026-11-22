package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.List;
import java.util.UUID;

import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;

/** 호출자에게 돌려주는 Build 상태, pack은 SUCCEEDED일 때만 */
public record BuildResult(
        UUID buildId,
        BuildStatus status,
        String processingConfigHash,
        List<DocumentHold> holds,
        String failureReason,
        PackRecord pack
) {

    /** 보류 목록 불변 사본 */
    public BuildResult {
        holds = List
                .copyOf(holds);
    }
}
