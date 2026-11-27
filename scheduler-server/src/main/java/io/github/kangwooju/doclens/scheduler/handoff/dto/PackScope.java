package io.github.kangwooju.doclens.scheduler.handoff.dto;

/** 발행 번호를 조회할 Pack 범위 */
public record PackScope(
        String project,
        String exactVersion
) {
}
