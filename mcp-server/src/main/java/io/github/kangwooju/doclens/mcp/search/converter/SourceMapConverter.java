package io.github.kangwooju.doclens.mcp.search.converter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.search.dto.CitationSource;
import io.github.kangwooju.doclens.mcp.search.dto.SourcePointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * documents.source_map_json과 chunks.source_map_json 열의 인용 출처 변환
 * 값이 없는 예전 Pack은 null과 빈 목록
 */
@Component
public class SourceMapConverter {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    /** 문서와 청크 source map JSON의 인용 출처 */
    public CitationSource toSource(
            String documentSourceMap,
            String chunkSourceMap
    ) {

        JsonNode document = JSON_MAPPER
                .readTree(documentSourceMap);
        JsonNode chunk = JSON_MAPPER
                .readTree(chunkSourceMap);
        Set<String> blockIds = new LinkedHashSet<>();
        List<SourcePointer> pointers = new ArrayList<>();
        String readerUrl = null;
        String sourceUrl = null;

        if (chunk
                .isArray()) {
            for (JsonNode entry : chunk) {
                SourcePointer pointer = pointer(entry);

                if (pointer
                        .blockId() != null) {
                    blockIds
                            .add(pointer
                                    .blockId());
                }

                pointers
                        .add(pointer);

                if (readerUrl == null) {
                    readerUrl = text(
                            entry
                                    .path("citation"),
                            "readerUrl"
                    );
                }

                if (sourceUrl == null) {
                    sourceUrl = text(
                            entry
                                    .path("citation"),
                            "sourceUrl"
                    );
                }
            }
        }

        return new CitationSource(
                readerUrl,
                sourceUrl,
                text(
                        document,
                        "originalUrl"
                ),
                text(
                        document,
                        "finalUrl"
                ),
                List
                        .copyOf(blockIds),
                pointers
        );
    }

    // source map 항목 하나의 원본 위치
    private static SourcePointer pointer(JsonNode entry) {

        return new SourcePointer(
                text(
                        entry,
                        "blockId"
                ),
                text(
                        entry,
                        "domPath"
                ),
                text(
                        entry,
                        "elementId"
                ),
                text(
                        entry,
                        "headingLocator"
                ),
                integer(
                        entry,
                        "blockOrdinal"
                ),
                integer(
                        entry,
                        "charStart"
                ),
                integer(
                        entry,
                        "charEnd"
                )
        );
    }

    // 문자열 필드 값, 없거나 null이면 null
    private static String text(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .path(field);

        return value
                .isString()
                ? value
                        .asString()
                : null;
    }

    // 정수 필드 값, 없거나 null이면 null
    private static Integer integer(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .path(field);

        return value
                .isIntegralNumber()
                ? value
                        .asInt()
                : null;
    }
}
