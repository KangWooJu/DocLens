package io.github.kangwooju.doclens.pipeline.parse.converter;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;

import tools.jackson.databind.json.JsonMapper;

/** 구조 산출물과 canonical JSON 사이 변환, 키는 record 구성요소 이름, enum은 이름 */
@Component
public class StructuredDocumentConverter {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    /** 구조 산출물의 canonical JSON, 저장 bytes와 hash의 기준 */
    public String toJson(StructuredDocument document) {

        return CanonicalJson
                .canonicalize(JSON_MAPPER
                        .writeValueAsString(document));
    }

    /** 저장한 JSON을 구조 산출물로 읽기 */
    public StructuredDocument fromJson(String json) {

        return JSON_MAPPER
                .readValue(
                        json,
                        StructuredDocument.class
                );
    }
}
