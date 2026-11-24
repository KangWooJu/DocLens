package io.github.kangwooju.doclens.pipeline.collected.exception;

/** 수집 회차 묶음이 계약과 어긋남, hash와 크기, 집합과 개수, 형식, 없는 객체, 모르는 문서 유형, 사람 확인 필요 */
public class CollectedRunInvalidException extends RuntimeException {

    /** 어긋난 항목과 내용 */
    public CollectedRunInvalidException(
            String subject,
            String detail
    ) {

        super("collected run " + subject + " is invalid: " + detail);
    }
}
