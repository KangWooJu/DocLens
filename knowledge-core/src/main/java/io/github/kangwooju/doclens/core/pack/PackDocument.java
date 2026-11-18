package io.github.kangwooju.doclens.core.pack;

import java.util.List;

/** Pack에 넣을 문서 하나와 그 섹션들(섹션 ordinal 오름차순) */
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
