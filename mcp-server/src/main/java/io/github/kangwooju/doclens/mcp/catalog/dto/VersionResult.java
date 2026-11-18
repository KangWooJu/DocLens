package io.github.kangwooju.doclens.mcp.catalog.dto;

import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;

/**
 * list_versions의 VERSION 결과 하나(D16), id는 `<packId>/<buildId>`
 */
public record VersionResult(
        String kind,
        String id,
        String text,
        Object citation,
        VersionAttributes attributes
) {

    /** 설치 Pack 기준 결과 */
    public static VersionResult from(InstalledPack pack) {

        return new VersionResult(
                "VERSION",
                pack
                        .packId() + "/" + pack
                                .buildId(),
                null,
                null,
                new VersionAttributes(
                        pack
                                .project(),
                        pack
                                .exactVersion(),
                        pack
                                .packId(),
                        pack
                                .buildId(),
                        pack
                                .checksum(),
                        null,
                        pack
                                .installedAt()
                                .toString(),
                        null,
                        pack
                                .capabilities()
                )
        );
    }
}
