package io.github.kangwooju.doclens.core.pack.dto;

import io.github.kangwooju.doclens.core.pack.support.Require;

/** 청크에서 다른 문서로 가는 링크, anchor가 없으면 빈 문자열 */
public record DocLink(
        String targetUrl,
        String targetAnchor
) {

    /** 필수 값 검사 */
    public DocLink {

        Require
                .text(
                        targetUrl,
                        "targetUrl"
                );
        Require
                .present(
                        targetAnchor,
                        "targetAnchor"
                );
    }
}
