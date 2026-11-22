package io.github.kangwooju.doclens.pipeline.pack.exception;

/** SUCCEEDED Build가 기록한 Pack 파일이 없거나 checksum이 다름, 같은 작업 키로는 다시 만들지 않음 */
public class BuildArtifactMissingException extends RuntimeException {

    /** Build와 기록된 object key */
    public BuildArtifactMissingException(
            String buildId,
            String objectKey
    ) {

        super("recorded pack " + objectKey + " of build " + buildId + " is missing or changed");
    }
}
