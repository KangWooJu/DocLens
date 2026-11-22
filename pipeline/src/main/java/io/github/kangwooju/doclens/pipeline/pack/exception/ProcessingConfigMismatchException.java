package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 요청한 처리 설정 hash가 지금 적용할 설정의 hash와 다름, Build 전에 거부 */
public class ProcessingConfigMismatchException extends RuntimeException {

    private final String requestedHash;
    private final String actualHash;

    /** 요청 hash와 실제 hash */
    public ProcessingConfigMismatchException(
            String requestedHash,
            String actualHash
    ) {

        super("processingConfigHash " + requestedHash + " does not match the applied configuration " + actualHash);
        this.requestedHash = requestedHash;
        this.actualHash = actualHash;
    }

    /** 요청 hash */
    public String requestedHash() {

        return requestedHash;
    }

    /** 실제 적용할 설정의 hash */
    public String actualHash() {

        return actualHash;
    }
}
