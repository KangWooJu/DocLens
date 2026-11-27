package io.github.kangwooju.doclens.pipeline.global.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ID 해시와 위치 ID 공식")
class IdHashTests {

    private static final Map<String, Vector> VECTORS = loadVectors();

    @Test
    @DisplayName("모든 golden vector의 인코딩 바이트와 ID 일치")
    void reproducesEveryGoldenVector() {

        for (Vector vector : VECTORS
                .values()) {
            String[] fields = vector
                    .fields()
                    .toArray(String[]::new);

            assertEquals(
                    vector
                            .encodedHex(),
                    HexFormat
                            .of()
                            .formatHex(IdHash
                                    .encode(fields)),
                    "encoding of " + vector
                            .name()
            );
            assertEquals(
                    vector
                            .id(),
                    IdHash
                            .hash(fields),
                    "id of " + vector
                            .name()
            );
        }
    }

    @Test
    @DisplayName("기존 청크 vector의 locator와 variant key를 공식으로 재계산")
    void recomputesLocatorsInsideChunkVectors() {

        Map<String, Function<Vector, String>> expected = new LinkedHashMap<>();
        expected
                .put(
                        "heading-duplicate-a",
                        vector -> IdHash
                                .headingLocator(
                                        "a",
                                        0
                                )
                );
        expected
                .put(
                        "heading-literal-a-at0",
                        vector -> IdHash
                                .headingLocator(
                                        "a@0",
                                        0
                                )
                );
        expected
                .put(
                        "heading-nfc-first",
                        vector -> IdHash
                                .headingLocator(
                                        "é",
                                        0
                                )
                );
        expected
                .put(
                        "heading-nfc-second",
                        vector -> IdHash
                                .headingLocator(
                                        "é",
                                        1
                                )
                );

        for (Map.Entry<String, Function<Vector, String>> entry : expected
                .entrySet()) {
            assertEquals(
                    field(
                            entry
                                    .getKey(),
                            2
                    ),
                    entry
                            .getValue()
                            .apply(VECTORS
                                    .get(entry
                                            .getKey())),
                    entry
                            .getKey()
            );
        }

        assertEquals(
                field(
                        "tab-duplicate-a",
                        3
                ),
                IdHash
                        .tabGroupLocator(
                                "a",
                                0
                        )
        );
        assertEquals(
                field(
                        "tab-literal-a-at0",
                        3
                ),
                IdHash
                        .tabGroupLocator(
                                "a@0",
                                0
                        )
        );
        assertEquals(
                field(
                        "tab-nfc-second",
                        3
                ),
                IdHash
                        .tabGroupLocator(
                                "é",
                                1
                        )
        );
        assertEquals(
                field(
                        "variant-duplicate-java",
                        4
                ),
                IdHash
                        .variantKey(
                                "java",
                                1,
                                true
                        )
        );
        assertEquals(
                field(
                        "variant-literal-java-at1",
                        4
                ),
                IdHash
                        .variantKey(
                                "java@1",
                                0,
                                true
                        )
        );
        assertEquals(
                field(
                        "variant-literal-none",
                        4
                ),
                IdHash
                        .variantKey(
                                "none",
                                0,
                                true
                        )
        );
        assertEquals(
                field(
                        "variant-nfc-second",
                        4
                ),
                IdHash
                        .variantKey(
                                "é",
                                1,
                                true
                        )
        );
    }

    @Test
    @DisplayName("청크 vector의 ID를 chunkId 공식으로 재계산")
    void recomputesChunkIds() {

        for (Vector vector : VECTORS
                .values()) {
            List<String> fields = vector
                    .fields();

            if (!fields
                    .getFirst()
                    .equals("chunk-v1")) {
                continue;
            }

            assertEquals(
                    vector
                            .id(),
                    IdHash
                            .chunkId(
                                    fields
                                            .get(1),
                                    fields
                                            .get(2),
                                    fields
                                            .get(3),
                                    fields
                                            .get(4),
                                    Integer
                                            .parseInt(fields
                                                    .get(5)),
                                    fields
                                            .get(6)
                            ),
                    vector
                            .name()
            );
        }
    }

    @Test
    @DisplayName("섹션, 블록, 탭 집합 공식 vector 일치")
    void matchesStepCFormulaVectors() {

        String headingLocator = IdHash
                .headingLocator(
                        "features.external-config",
                        0
                );
        String sectionId = IdHash
                .sectionId(
                        "sha256:" + "a"
                                .repeat(64),
                        headingLocator
                );

        assertEquals(
                id("heading-locator-html-id"),
                headingLocator
        );
        assertEquals(
                id("heading-locator-ordinal"),
                IdHash
                        .headingLocator(List
                                .of(
                                        0,
                                        3
                                ))
        );
        assertEquals(
                id("tabgroup-locator-ordinal"),
                IdHash
                        .tabGroupLocator(4)
        );
        assertEquals(
                hex("variant-label-arbitrary"),
                IdHash
                        .variantKey(
                                "Logback",
                                0,
                                false
                        )
        );
        assertEquals(
                id("section"),
                sectionId
        );
        assertEquals(
                id("block"),
                IdHash
                        .blockId(
                                sectionId,
                                3
                        )
        );
        assertEquals(
                id("tabgroup-set"),
                IdHash
                        .tabGroupLocatorOf(List
                                .of(
                                        id("tabgroup-locator-html-id"),
                                        id("tabgroup-locator-ordinal")
                                ))
        );
        assertEquals(
                hex("variant-set"),
                IdHash
                        .variantKeyOf(List
                                .of(
                                        "java",
                                        "yaml"
                                ))
        );
    }

    @Test
    @DisplayName("대응표 밖 원문 라벨 java는 literal key가 아닌 64자리 hex")
    void unmappedLiteralLabelIsHashed() {

        assertEquals(
                "java",
                IdHash
                        .variantKey(
                                "java",
                                0,
                                true
                        )
        );
        assertEquals(
                hex("variant-label-literal-unmapped"),
                IdHash
                        .variantKey(
                                "java",
                                0,
                                false
                        )
        );
    }

    @Test
    @DisplayName("같은 heading 위치라도 문서가 다르면 섹션 ID가 다름")
    void sectionIdsDifferAcrossDocuments() {

        assertNotEquals(
                id("section"),
                id("section-other-document")
        );
    }

    @Test
    @DisplayName("탭 그룹 0개는 none, 1개는 원래 값")
    void tabIdentityForZeroAndOneGroup() {

        assertEquals(
                IdHash.NONE,
                IdHash
                        .tabGroupLocatorOf(List
                                .of())
        );
        assertEquals(
                IdHash.NONE,
                IdHash
                        .variantKeyOf(List
                                .of())
        );
        assertEquals(
                "kotlin",
                IdHash
                        .variantKeyOf(List
                                .of("kotlin"))
        );
    }

    @Test
    @DisplayName("짝 없는 surrogate 필드 거절")
    void rejectsUnpairedSurrogate() {

        assertThrows(
                IllegalArgumentException.class,
                () -> IdHash
                        .hash(
                                "test-v1",
                                "\ud800"
                        )
        );
    }

    // 이름이 붙은 vector 조회
    private static Vector vector(String name) {

        Vector vector = VECTORS
                .get(name);

        if (vector == null) {
            throw new IllegalStateException("missing golden vector " + name);
        }

        return vector;
    }

    // vector의 ID
    private static String id(String name) {

        return vector(name)
                .id();
    }

    // sha256: 접두를 뺀 vector ID
    private static String hex(String name) {

        return id(name)
                .substring("sha256:"
                        .length());
    }

    // vector의 index번째 필드
    private static String field(
            String name,
            int index
    ) {

        return vector(name)
                .fields()
                .get(index);
    }

    // contracts/v1/id-golden-vectors.json 읽기, 이름 순서 보존
    private static Map<String, Vector> loadVectors() {

        List<Vector> vectors = JsonMapper
                .builder()
                .build()
                .readValue(
                        Path
                                .of(System
                                        .getProperty("doclens.contractsDir"))
                                .resolve("id-golden-vectors.json")
                                .toFile(),
                        new TypeReference<>() {
                        }
                );
        Map<String, Vector> byName = new LinkedHashMap<>();
        vectors
                .forEach(vector -> byName
                        .put(
                                vector
                                        .name(),
                                vector
                        ));

        return byName;
    }

    /** golden vector 한 개 */
    record Vector(
            String name,
            List<String> fields,
            String encodedHex,
            String id
    ) {
    }
}
