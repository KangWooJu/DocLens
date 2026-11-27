package io.github.kangwooju.doclens.pipeline.pack.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.pipeline.pack.BuildFixtures;

@DisplayName("Pack 문서 canonical JSON 변환")
class PackDocumentConverterTests {

    private final PackDocumentConverter converter = new PackDocumentConverter();

    @Test
    @DisplayName("fixture마다 JSON 왕복이 같은 Pack 문서, 출력은 canonical")
    void roundTripsFixtures() {

        for (String fixture : BuildFixtures.FIXTURES) {
            PackDocument document = BuildFixtures
                    .packDocument(fixture);
            String json = converter
                    .toJson(document);

            assertEquals(
                    document,
                    converter
                            .fromJson(json),
                    fixture
            );
            assertEquals(
                    json,
                    CanonicalJson
                            .canonicalize(json),
                    fixture
            );
        }
    }
}
