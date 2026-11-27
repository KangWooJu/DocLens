package io.github.kangwooju.doclens.scheduler.dispatch.type;

/** 실패한 run 단계, requeue가 되돌릴 단계 */
public enum FailedStage {

    COLLECTING,
    PROCESSING
}
