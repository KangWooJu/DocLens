package io.github.kangwooju.doclens.mcp.catalog.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;

/**
 * list_projects의 PROJECT 결과 하나(D16), text·citation은 항상 null
 */
public record ProjectResult(
        String kind,
        String id,
        String text,
        Object citation,
        ProjectAttributes attributes
) {

    /** 한 프로젝트의 설치 Pack 묶음 기준 결과 */
    public static ProjectResult from(
            String projectId,
            List<InstalledPack> packs
    ) {

        long versionCount = packs
                .stream()
                .map(InstalledPack::exactVersion)
                .distinct()
                .count();

        return new ProjectResult(
                "PROJECT",
                projectId,
                null,
                null,
                new ProjectAttributes(
                        projectId,
                        projectId,
                        versionCount,
                        packs
                                .size()
                )
        );
    }
}
