package io.github.kangwooju.doclens.mcp.search.dto;

import java.util.List;
import java.util.stream.Stream;

import io.github.kangwooju.doclens.core.pack.DocumentType;
import io.github.kangwooju.doclens.mcp.global.response.InvalidToolInputException;

/**
 * search_docs 입력, documentTypes는 중복 제거 뒤 알려진 Pack 문서 유형만 허용
 */
public record SearchRequest(
        String query,
        String project,
        String exactVersion,
        String buildId,
        int topK,
        List<String> documentTypes
) {

    /** Pack 문서 유형 값 10종 */
    public static final List<String> DOCUMENT_TYPES = Stream
            .of(DocumentType
                    .values())
            .map(DocumentType::wireValue)
            .toList();

    public SearchRequest {

        documentTypes = documentTypes
                .stream()
                .distinct()
                .toList();

        if (!DOCUMENT_TYPES
                .containsAll(documentTypes)) {
            throw new InvalidToolInputException("unknown document type");
        }
    }
}
