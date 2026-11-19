package io.github.kangwooju.doclens.mcp.catalog.dto;

import java.util.List;

/** VERSION 결과 속성, publicationSequence와 lastConfirmedAt은 아직 null */
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

    /** capabilities 불변 사본 */
    public VersionAttributes {

        capabilities = List
                .copyOf(capabilities);
    }
}
