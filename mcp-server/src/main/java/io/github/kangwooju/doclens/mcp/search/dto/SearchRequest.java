package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;

/** search_docs 입력, documentTypes는 중복 제거 */
public record SearchRequest(
        String query,
        String project,
        String exactVersion,
        String buildId,
        int topK,
        List<String> documentTypes
) {

    /** 허용하는 문서 유형 값 */
    public static final List<String> DOCUMENT_TYPES = DocumentType
            .wireValues();

    /** documentTypes 중복 제거 */
    public SearchRequest {

        documentTypes = documentTypes
                .stream()
                .distinct()
                .toList();
    }
}
