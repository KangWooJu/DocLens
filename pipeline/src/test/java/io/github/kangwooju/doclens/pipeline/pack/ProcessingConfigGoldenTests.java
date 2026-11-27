package io.github.kangwooju.doclens.pipeline.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.pack.converter.PackDocumentConverter;
import io.github.kangwooju.doclens.pipeline.parse.converter.StructuredDocumentConverter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 처리 설정 hash가 같으면 fixture 출력도 같음
 * 동작을 바꾸면 정책 버전을 올리고 골든 파일에 줄을 더함, 기존 줄은 CI가 지우지 못하게 막음
 */
@DisplayName("처리 설정별 골든 출력")
class ProcessingConfigGoldenTests {

    private static final String GOLDEN = "pipeline/src/test/resources/golden/processing-outputs.jsonl";

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .build();

    @Test
    @DisplayName("현재 처리 설정 hash의 fixture 출력 sha가 골든 줄과 같음")
    void matchesGoldenOutputs() {

        String hash = BuildFixtures
                .currentHash();
        Map<String, Map<String, String>> golden = golden(hash);

        for (String fixture : BuildFixtures.FIXTURES) {
            Map<String, String> actual = line(
                    hash,
                    fixture
            );
            Map<String, String> expected = golden
                    .get(fixture);

            assertNotNull(
                    expected,
                    "no golden line for this configuration and fixture, add to " + GOLDEN + ": " + CanonicalJson
                            .write(new HashMap<String, Object>(actual))
            );
            assertEquals(
                    expected,
                    actual,
                    "output changed without a processing configuration change: bump the parser or chunker policy version, then add the new line to " + GOLDEN
            );
        }
    }

    // fixture 하나의 현재 출력 줄
    private static Map<String, String> line(
            String hash,
            String fixture
    ) {

        String structured = new StructuredDocumentConverter()
                .toJson(BuildFixtures
                        .structured(fixture));
        String packDocument = new PackDocumentConverter()
                .toJson(BuildFixtures
                        .packDocument(fixture));

        return Map
                .of(
                        "processingConfigHash",
                        hash,
                        "fixture",
                        fixture,
                        "structuredSha256",
                        CanonicalJson
                                .sha256(structured),
                        "packDocumentSha256",
                        CanonicalJson
                                .sha256(packDocument)
                );
    }

    // 골든 파일에서 hash가 같은 줄을 fixture 이름으로
    private static Map<String, Map<String, String>> golden(String hash) {

        Map<String, Map<String, String>> byFixture = new HashMap<>();

        for (String text : lines()) {
            if (text
                    .isBlank()) {
                continue;
            }

            Map<String, String> entry = JSON
                    .readValue(
                            text,
                            new TypeReference<Map<String, String>>() {
                            }
                    );

            if (hash
                    .equals(entry
                            .get("processingConfigHash"))) {
                byFixture
                        .put(
                                entry
                                        .get("fixture"),
                                entry
                        );
            }
        }

        return byFixture;
    }

    // 골든 파일 줄 목록
    private static List<String> lines() {

        try (InputStream input = ProcessingConfigGoldenTests.class
                .getResourceAsStream("/golden/processing-outputs.jsonl")) {
            if (input == null) {
                throw new IllegalStateException("missing " + GOLDEN);
            }

            return new String(
                    input
                            .readAllBytes(),
                    StandardCharsets.UTF_8
            )
                    .lines()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
