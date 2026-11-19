package io.github.kangwooju.doclens.core.pack.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.support.Require;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;

/** Pack에 넣을 문서와 섹션 목록(ordinal 순) */
public record PackDocument(
        String documentId,
        String url,
        String title,
        DocumentType documentType,
        String exactVersion,
        String sourceSnapshotId,
        String rawSha256,
        String sourceMapJson,
        List<PackSection> sections
) {

    /** 필수 값과 ID 형식 검사, 섹션 목록 불변 사본 */
    public PackDocument {

        Require
                .sha256Id(
                        documentId,
                        "documentId"
                );
        Require
                .text(
                        url,
                        "url"
                );
        Require
                .text(
                        title,
                        "title"
                );
        Require
                .present(
                        documentType,
                        "documentType"
                );
        Require
                .text(
                        exactVersion,
                        "exactVersion"
                );
        Require
                .text(
                        sourceSnapshotId,
                        "sourceSnapshotId"
                );
        Require
                .text(
                        rawSha256,
                        "rawSha256"
                );
        Require
                .text(
                        sourceMapJson,
                        "sourceMapJson"
                );
        sections = List
                .copyOf(sections);
    }
}
