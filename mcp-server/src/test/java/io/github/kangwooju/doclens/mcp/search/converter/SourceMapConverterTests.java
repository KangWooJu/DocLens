package io.github.kangwooju.doclens.mcp.search.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.mcp.search.dto.CitationSource;
import io.github.kangwooju.doclens.mcp.search.dto.SourcePointer;

@DisplayName("source map 인용 출처 변환")
class SourceMapConverterTests {

    private static final String DOCUMENT = "{\"originalUrl\":\"https://raw.example/a.adoc\","
            + "\"finalUrl\":\"https://raw.example/b.adoc\"}";

    private static final String CHUNK = "["
            + "{\"blockId\":\"block-a\",\"blockOrdinal\":0,\"charEnd\":10,\"charStart\":0,"
            + "\"citation\":{\"readerUrl\":\"https://docs.example/a.html#x\","
            + "\"sourceUrl\":\"https://github.example/a.adoc#L3\"},"
            + "\"domPath\":\"1/0\",\"elementId\":null,\"headingLocator\":\"heading-a\"},"
            + "{\"blockId\":\"block-b\",\"blockOrdinal\":1,\"charEnd\":null,\"charStart\":null,"
            + "\"domPath\":\"1/1\",\"elementId\":\"b\",\"headingLocator\":\"heading-a\"},"
            + "{\"blockId\":\"block-a\",\"blockOrdinal\":0,\"charEnd\":20,\"charStart\":10,"
            + "\"domPath\":\"1/0\",\"elementId\":null,\"headingLocator\":\"heading-a\"}"
            + "]";

    @Test
    @DisplayName("문서 주소, 첫 공개 주소와 원본 주소, 순서 유지 중복 없는 blockIds, 항목별 원본 위치")
    void readsSourceFields() {

        CitationSource source = new SourceMapConverter()
                .toSource(
                        DOCUMENT,
                        CHUNK
                );

        assertEquals(
                "https://docs.example/a.html#x",
                source
                        .readerUrl()
        );
        assertEquals(
                "https://github.example/a.adoc#L3",
                source
                        .sourceUrl()
        );
        assertEquals(
                "https://raw.example/a.adoc",
                source
                        .originalUrl()
        );
        assertEquals(
                "https://raw.example/b.adoc",
                source
                        .finalUrl()
        );
        assertEquals(
                List
                        .of(
                                "block-a",
                                "block-b"
                        ),
                source
                        .blockIds()
        );
        assertEquals(
                List
                        .of(
                                new SourcePointer(
                                        "block-a",
                                        "1/0",
                                        null,
                                        "heading-a",
                                        0,
                                        0,
                                        10
                                ),
                                new SourcePointer(
                                        "block-b",
                                        "1/1",
                                        "b",
                                        "heading-a",
                                        1,
                                        null,
                                        null
                                ),
                                new SourcePointer(
                                        "block-a",
                                        "1/0",
                                        null,
                                        "heading-a",
                                        0,
                                        10,
                                        20
                                )
                        ),
                source
                        .sourcePointer()
        );
    }

    @Test
    @DisplayName("출처 값 없는 예전 Pack의 null과 빈 목록")
    void toleratesEmptySourceMaps() {

        CitationSource source = new SourceMapConverter()
                .toSource(
                        "{}",
                        "{}"
                );

        assertNull(source
                .readerUrl());
        assertNull(source
                .sourceUrl());
        assertNull(source
                .originalUrl());
        assertNull(source
                .finalUrl());
        assertTrue(source
                .blockIds()
                .isEmpty());
        assertTrue(source
                .sourcePointer()
                .isEmpty());
    }
}
