package io.github.kangwooju.doclens.pipeline.pack.exception;

/** Export할 수 없는 Build, 없거나 보류 또는 실패 상태이거나 Pack 크기 상한을 넘음 */
public class BuildNotExportableException extends RuntimeException {

    /** Build와 이유 */
    public BuildNotExportableException(
            String buildId,
            String reason
    ) {

        super("build " + buildId + " cannot be exported: " + reason);
    }
}
