package io.github.kangwooju.doclens.scheduler.schedule.type;

/** slot 등록과 requeue 결과, 명령 종료 코드 포함 */
public enum CommandOutcome {

    CREATED(0),
    DUPLICATE_SLOT(0),
    ACTIVE_RUN_EXISTS(3),
    REQUEUED(0),
    NOT_FAILED(4),
    SUPERSEDED(5);

    private final int exitCode;

    // 결과별 종료 코드
    CommandOutcome(int exitCode) {

        this.exitCode = exitCode;
    }

    /** 명령 종료 코드 */
    public int exitCode() {

        return exitCode;
    }
}
