package io.github.kangwooju.doclens.mcp.catalog.dto;

import java.util.List;

/**
 * VERSION 결과 속성(D16)
 *
 * <p>publicationSequence는 Pack에 없어 null, lastConfirmedAt은 서버 확인이 없어 null
 */
public record VersionAttributes(
        String projectId,
        String exactVersion,
        String packId,
        String buildId,
        String checksum,
        Long publicationSequence,
        String installedAt,
        String lastConfirmedAt,
        List<String> capabilities
) {

    public VersionAttributes {

        capabilities = List
                .copyOf(capabilities);
    }
}
