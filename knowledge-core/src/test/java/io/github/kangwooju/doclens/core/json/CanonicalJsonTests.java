package io.github.kangwooju.doclens.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("CanonicalJson")
class CanonicalJsonTests {

    private static final Path CONTRACTS = Path
            .of(System
                    .getProperty("doclens.contractsDir"));

    @Test
    @DisplayName("계약 golden vector와 바이트·해시 일치")
    void matchesContractGoldenVectors() {

        List<Map<String, Object>> vectors = JsonMapper
                .builder()
                .build()
                .readValue(
                        CONTRACTS
                                .resolve("canonical-golden-vectors.json")
                                .toFile(),
                        new TypeReference<>() {
                        }
                );

        assertTrue(!vectors
                .isEmpty());

        for (Map<String, Object> vector : vectors) {
            String canonical = CanonicalJson
                    .write(vector
                            .get("input"));
            String hex = HexFormat
                    .of()
                    .formatHex(canonical
                            .getBytes(StandardCharsets.UTF_8));

            assertEquals(
                    vector
                            .get("canonicalUtf8Hex"),
                    hex,
                    "bytes of " + vector
                            .get("name")
            );
            assertEquals(
                    vector
                            .get("hash"),
                    CanonicalJson
                            .sha256(canonical),
                    "hash of " + vector
                            .get("name")
            );
        }
    }

    @Test
    @DisplayName("UTF-16이 아닌 code point 기준 키 정렬")
    void sortsKeysByCodePointNotUtf16() {

        // U+E000(BMP)은 U+1F600보다 code point가 작지만 UTF-16 정렬에서는 뒤쪽
        String canonical = CanonicalJson
                .write(Map
                        .of(
                                "😀",
                                1,
                                "",
                                2
                        ));

        assertEquals(
                "{\"\":2,\"😀\":1}",
                canonical
        );
    }

    @Test
    @DisplayName("실수와 중복 키 거절")
    void rejectsFloatsAndDuplicateKeys() {

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJson
                        .canonicalize("{\"a\":1.5}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJson
                        .canonicalize("{\"a\":1,\"a\":2}")
        );
    }
}
