package io.github.kangwooju.doclens.mcp.catalog.dto;

/**
 * PROJECT 결과 속성(D16), displayName은 Pack에 이름이 없어 projectId 그대로
 */
public record ProjectAttributes(
        String projectId,
        String displayName,
        long installedVersionCount,
        long installedBuildCount
) {
}
