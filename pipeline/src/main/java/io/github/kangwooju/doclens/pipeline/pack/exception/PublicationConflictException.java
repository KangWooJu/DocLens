package io.github.kangwooju.doclens.pipeline.pack.exception;

/** 기대한 발행 번호가 Pack의 현재 최대 번호와 달라 발행하지 못한 Build, Build는 SEALED로 남아 맞는 번호로 다시 보내면 재개 */
public class PublicationConflictException extends RuntimeException {

    /** Build와 기대한 발행 번호 */
    public PublicationConflictException(
            String buildId,
            Long expected
    ) {

        super("build " + buildId + " was not published: expected publication sequence " + expected + " is no longer the latest");
    }
}
