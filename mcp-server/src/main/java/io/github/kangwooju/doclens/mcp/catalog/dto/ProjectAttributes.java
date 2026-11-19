package io.github.kangwooju.doclens.mcp.catalog.dto;

/** PROJECT 결과 속성, displayName은 Pack에 이름이 없어 projectId 사용 */
public record ProjectAttributes(
        String projectId,
        String displayName,
        long installedVersionCount,
        long installedBuildCount
) {
}
