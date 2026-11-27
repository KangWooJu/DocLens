package io.github.kangwooju.doclens.pipeline.collected.dto;

import java.util.List;
import java.util.UUID;

/** contracts processingRunResultReceipt, 결과 객체는 publication manifest */
public record ProcessingReceipt(
        String runInputSnapshotHash,
        List<ProjectionResult> projectionResults,
        ObjectRef resultObjectRef
) {

    /** projection 결과 목록 불변 사본 */
    public ProcessingReceipt {
        projectionResults = List
                .copyOf(projectionResults);
    }

    /** projection 하나의 결과, buildJobId는 buildId와 같은 값 */
    public record ProjectionResult(
            PackScope packScope,
            String state,
            UUID buildId,
            UUID buildJobId,
            UUID activationJobId
    ) {
    }

    /** contracts packScope */
    public record PackScope(
            String project,
            String exactVersion,
            String applicability
    ) {
    }
}
