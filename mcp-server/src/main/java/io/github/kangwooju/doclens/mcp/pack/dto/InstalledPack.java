package io.github.kangwooju.doclens.mcp.pack.dto;

import java.time.Instant;
import java.util.List;

/** 시작할 때 검증을 통과한 설치 Pack, installedAt은 파일 수정 시각 */
public record InstalledPack(
        String packId,
        String buildId,
        String project,
        String exactVersion,
        String checksum,
        Instant installedAt,
        List<String> capabilities
) {

    /** capabilities 불변 사본 */
    public InstalledPack {

        capabilities = List
                .copyOf(capabilities);
    }
}
