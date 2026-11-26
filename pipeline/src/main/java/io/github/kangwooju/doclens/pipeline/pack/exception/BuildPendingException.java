package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 원본 객체가 아직 없어 RUNNING으로 남은 Build, 같은 요청을 다시 보내면 이어 감 */
public class BuildPendingException extends RuntimeException {

    /** RUNNING Build */
    public BuildPendingException(String buildId) {

        super("build " + buildId + " is still running: some raw objects are not stored yet");
    }
}
