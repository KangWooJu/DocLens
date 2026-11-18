package io.github.kangwooju.doclens.mcp.pack.dto;

import java.time.Instant;
import java.util.List;

/**
 * 시작 시 검증을 통과한 설치 Pack 하나
 *
 * <p>checksum은 파일 전체 SHA-256(`sha256:` 접두어), installedAt은 파일 수정 시각(초 단위)
 */
public record InstalledPack(
        String packId,
        String buildId,
        String project,
        String exactVersion,
        String checksum,
        Instant installedAt,
        List<String> capabilities
) {

    public InstalledPack {

        capabilities = List
                .copyOf(capabilities);
    }
}
