package io.github.kangwooju.doclens.mcp.search.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * sections.heading_path JSON 배열 열의 문자열 목록 변환
 */
@Component
public class HeadingPathConverter {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /** JSON 배열 문자열의 목록 변환 */
    public List<String> toList(String json) {

        return JSON_MAPPER
                .readValue(
                        json,
                        STRING_LIST
                );
    }
}
