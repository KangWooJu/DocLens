package io.github.kangwooju.doclens.core.pack.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.support.Require;

/** 검색 단위 청크와 코드 변형, 식별자, 문서 링크 */
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

    /** 필수 값과 ID 형식 검사, 목록 불변 사본 */
    public PackChunk {

        Require
                .sha256Id(
                        chunkId,
                        "chunkId"
                );
        Require
                .nonNegative(
                        ordinal,
                        "chunk ordinal"
                );
        Require
                .present(
                        content,
                        "content"
                );
        Require
                .present(
                        headingPath,
                        "headingPath"
                );
        Require
                .text(
                        blocksJson,
                        "blocksJson"
                );
        Require
                .text(
                        sourceMapJson,
                        "sourceMapJson"
                );
        codeVariants = List
                .copyOf(codeVariants);
        identifiers = List
                .copyOf(identifiers);
        docLinks = List
                .copyOf(docLinks);
    }
}
