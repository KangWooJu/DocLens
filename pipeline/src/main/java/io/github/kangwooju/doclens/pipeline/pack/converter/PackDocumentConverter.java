package io.github.kangwooju.doclens.pipeline.pack.converter;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;

import tools.jackson.databind.json.JsonMapper;

/** Pack 문서와 canonical JSON 사이 변환, CHUNK 산출물의 저장 bytes와 hash 기준 */
@Component
public class PackDocumentConverter {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    /** Pack 문서의 canonical JSON */
    public String toJson(PackDocument document) {

        return CanonicalJson
                .canonicalize(JSON_MAPPER
                        .writeValueAsString(document));
    }

    /** 저장한 JSON을 Pack 문서로 읽기 */
    public PackDocument fromJson(String json) {

        return JSON_MAPPER
                .readValue(
                        json,
                        PackDocument.class
                );
    }
}
