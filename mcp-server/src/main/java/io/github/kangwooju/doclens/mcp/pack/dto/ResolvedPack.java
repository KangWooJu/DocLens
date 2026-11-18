package io.github.kangwooju.doclens.mcp.pack.dto;

/**
 * 응답의 resolved·installedBuilds 항목(D16)
 *
 * <p>publicationSequence는 Pack에 없는 값이라 null(C·D 단계에서 채울 공백)
 */
public record ResolvedPack(
        String packId,
        String buildId,
        String checksum,
        String exactVersion,
        Long publicationSequence
) {

    /** 설치 Pack 기준 resolved 값 */
    public static ResolvedPack from(InstalledPack pack) {

        return new ResolvedPack(
                pack
                        .packId(),
                pack
                        .buildId(),
                pack
                        .checksum(),
                pack
                        .exactVersion(),
                null
        );
    }
}
