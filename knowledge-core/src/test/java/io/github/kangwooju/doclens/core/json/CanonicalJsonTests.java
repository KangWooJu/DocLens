package io.github.kangwooju.doclens.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class CanonicalJsonTests {

    private static final Path CONTRACTS = Path.of(System.getProperty("doclens.contractsDir"));

    @Test
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

        assertTrue(!vectors.isEmpty());

        for (Map<String, Object> vector : vectors) {
            String canonical = CanonicalJson.write(vector.get("input"));
            String hex = HexFormat
                    .of()
                    .formatHex(canonical.getBytes(StandardCharsets.UTF_8));

            assertEquals(
                    vector.get("canonicalUtf8Hex"),
                    hex,
                    "bytes of " + vector.get("name")
            );
            assertEquals(
                    vector.get("hash"),
                    CanonicalJson.sha256(canonical),
                    "hash of " + vector.get("name")
            );
        }
    }

    @Test
    void sortsKeysByCodePointNotUtf16() {

        // U+E000(BMP)는 U+1F600보다 code point가 작지만 UTF-16에서는 뒤에 온다.
        String canonical = CanonicalJson.write(Map.of(
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
    void rejectsFloatsAndDuplicateKeys() {

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJson.canonicalize("{\"a\":1.5}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJson.canonicalize("{\"a\":1,\"a\":2}")
        );
    }
}
