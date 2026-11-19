package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;

/** PARSE 단계 결과이자 영속할 구조 산출물, 섹션은 문서 DOM 순서 */
public record StructuredDocument(
        int schemaVersion,
        String documentId,
        String project,
        String exactVersion,
        DocumentType documentType,
        String canonicalUrl,
        String title,
        Provenance provenance,
        List<StructuredSection> sections
) {

    /** 구조 산출물 형식 버전 */
    public static final int SCHEMA_VERSION = 1;

    /** 섹션 목록 불변 사본 */
    public StructuredDocument {
        sections = List
                .copyOf(sections);
    }
}
