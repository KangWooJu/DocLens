package io.github.kangwooju.doclens.mcp.pack.dto;

/** 응답에 넣는 Pack 정보, publicationSequence는 아직 null */
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
