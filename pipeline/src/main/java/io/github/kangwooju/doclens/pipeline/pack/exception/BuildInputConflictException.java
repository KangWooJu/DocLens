package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 같은 작업 키의 Build가 다른 원본으로 이미 있음, 재시도로 해결되지 않음 */
public class BuildInputConflictException extends RuntimeException {

    /** 충돌한 작업 키 */
    public BuildInputConflictException(String jobKey) {

        super("build " + jobKey + " already exists with different sources");
    }
}
