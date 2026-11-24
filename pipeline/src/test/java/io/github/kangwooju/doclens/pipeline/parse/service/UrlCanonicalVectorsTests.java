package io.github.kangwooju.doclens.pipeline.parse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * contracts URL 벡터의 링크를 현재 파서로 해석
 * canonical은 collector 규칙, javaTarget은 현재 파서 결과, 둘이 다른 collector 대상은 알려진 차이 목록으로 고정
 */
@DisplayName("contracts URL 벡터와 파서 링크 해석")
class UrlCanonicalVectorsTests {

    // collector 대상 URL인데 파서 targetUrl이 다른 벡터, 파서를 맞추면 정책 버전을 올리는 별도 작업
    private static final List<String> KNOWN_DIFFERENCES = List
            .of(
                    "empty-href",
                    "http-approved-host",
                    "uppercase-host",
                    "default-port",
                    "percent-hex-uppercase",
                    "percent-unreserved-decoded",
                    "non-ascii-encoded",
                    "non-ascii-nfc",
                    "space-encoded"
            );

    @Test
    @DisplayName("벡터마다 base를 finalUrl로 한 문서의 링크 targetUrl이 javaTarget과 같음")
    void resolvesEveryVectorAsRecorded() {

        JsonNode document = JsonMapper
                .builder()
                .build()
                .readTree(Path
                        .of(System
                                .getProperty("doclens.contractsDir"))
                        .resolve("url-canonical-vectors.json")
                        .toFile());
        List<String> known = new ArrayList<>();

        for (JsonNode vector : document
                .get("vectors")
                .values()) {
            String name = vector
                    .get("name")
                    .stringValue();
            String javaTarget = vector
                    .get("javaTarget")
                    .stringValue(null);

            assertEquals(
                    javaTarget,
                    targetUrl(
                            vector
                                    .get("base")
                                    .stringValue(),
                            vector
                                    .get("href")
                                    .stringValue()
                    ),
                    name
            );

            String canonical = vector
                    .get("canonical")
                    .stringValue(null);

            if (canonical != null && !canonical
                    .equals(javaTarget)) {
                known
                        .add(name);
            }
        }

        assertEquals(
                KNOWN_DIFFERENCES,
                known
        );
    }

    // base 주소 문서 안 링크 하나의 targetUrl
    private static String targetUrl(
            String base,
            String href
    ) {

        byte[] bytes = ParseFixtures
                .page("<div class=\"paragraph\"><p><a href=\"" + href
                        .replace(
                                "&",
                                "&amp;"
                        )
                        .replace(
                                "\"",
                                "&quot;"
                        ) + "\">link</a></p></div>")
                .getBytes(StandardCharsets.UTF_8);
        List<BlockLink> links = ParseFixtures
                .service()
                .parse(new SourceDocument(
                        ParseFixtures.DOCUMENT_ID,
                        "spring-boot",
                        "4.1.2",
                        DocumentType.REFERENCE,
                        base,
                        base,
                        base,
                        "sha256:" + "5"
                                .repeat(64),
                        CollectedRunFixtures
                                .sha256(bytes),
                        bytes
                ))
                .sections()
                .getFirst()
                .blocks()
                .getFirst()
                .links();

        assertEquals(
                1,
                links
                        .size(),
                href
        );

        return links
                .getFirst()
                .targetUrl();
    }
}
