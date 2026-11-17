package io.github.kangwooju.doclens.core.pack;

import java.util.List;

/** 검색 단위 청크와 그에 딸린 코드 변형·식별자·문서 링크. */
public record PackChunk(
        String chunkId,
        int ordinal,
        String content,
        String headingPath,
        String blocksJson,
        String sourceMapJson,
        List<CodeVariant> codeVariants,
        List<String> identifiers,
        List<DocLink> docLinks
) {

    public PackChunk {

        Require.sha256Id(chunkId, "chunkId");
        Require.nonNegative(ordinal, "chunk ordinal");
        Require.present(content, "content");
        Require.present(headingPath, "headingPath");
        Require.text(blocksJson, "blocksJson");
        Require.text(sourceMapJson, "sourceMapJson");
        codeVariants = List.copyOf(codeVariants);
        identifiers = List.copyOf(identifiers);
        docLinks = List.copyOf(docLinks);
    }
}
