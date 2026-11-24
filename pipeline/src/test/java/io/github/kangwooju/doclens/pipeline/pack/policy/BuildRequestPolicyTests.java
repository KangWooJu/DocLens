package io.github.kangwooju.doclens.pipeline.pack.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;

@DisplayName("Build 요청의 include 검사")
class BuildRequestPolicyTests {

    private static final String RAW_ROOT = "https://raw.githubusercontent.com/spring-projects/spring-boot/0123456789abcdef0123456789abcdef01234567/";

    private static final SourceEntry PAGE = entry(
            "pages/sample.adoc",
            DocumentType.REFERENCE
    );

    private static final SourceEntry EXAMPLE = entry(
            "src/main/java/Sample.java",
            DocumentType.EXAMPLE
    );

    private final BuildRequestPolicy policy = new BuildRequestPolicy();

    @Test
    @DisplayName("include 목록 null은 거부, 빈 목록과 원본과 겹치지 않는 include는 허용")
    void requiresIncludeList() {

        assertInvalid(
                List
                        .of(PAGE),
                null
        );
        assertDoesNotThrow(() -> policy
                .requireValid(request(
                        List
                                .of(PAGE),
                        List
                                .of()
                )));
        assertDoesNotThrow(() -> policy
                .requireValid(request(
                        List
                                .of(PAGE),
                        List
                                .of(EXAMPLE)
                )));
    }

    @Test
    @DisplayName("include도 원본과 같은 형식 검사, hash 형식과 raw key와 dot segment")
    void checksIncludeLikeSource() {

        assertInvalid(
                List
                        .of(PAGE),
                List
                        .of(with(
                                "not-a-hash",
                                EXAMPLE
                                        .rawObjectKey()
                        ))
        );
        assertInvalid(
                List
                        .of(PAGE),
                List
                        .of(with(
                                EXAMPLE
                                        .rawSha256(),
                                "packs/spring-boot/example.txt"
                        ))
        );
        assertInvalid(
                List
                        .of(PAGE),
                List
                        .of(with(
                                EXAMPLE
                                        .rawSha256(),
                                "raw/spring-boot/../example.txt"
                        ))
        );
    }

    @Test
    @DisplayName("include끼리 documentId 중복과 원본과 겹치는 documentId는 거부")
    void rejectsDuplicateDocumentIds() {

        assertInvalid(
                List
                        .of(PAGE),
                List
                        .of(
                                EXAMPLE,
                                EXAMPLE
                        )
        );
        assertInvalid(
                List
                        .of(PAGE),
                List
                        .of(PAGE)
        );
    }

    @Test
    @DisplayName("원본과 include 합계가 최대 문서 수를 넘으면 거부")
    void limitsTotalDocuments() {

        List<SourceEntry> includes = new ArrayList<>();

        for (int index = 0; index < BuildRequestPolicy.MAX_DOCUMENTS; index++) {
            includes
                    .add(entry(
                            "src/main/java/Example" + index + ".java",
                            DocumentType.EXAMPLE
                    ));
        }

        assertInvalid(
                List
                        .of(PAGE),
                includes
        );
    }

    // 요청 검사 실패
    private void assertInvalid(
            List<SourceEntry> sources,
            List<SourceEntry> includes
    ) {

        assertThrows(
                IllegalArgumentException.class,
                () -> policy
                        .requireValid(request(
                                sources,
                                includes
                        ))
        );
    }

    // 고정 범위 값의 요청
    private static BuildRequest request(
            List<SourceEntry> sources,
            List<SourceEntry> includes
    ) {

        return new BuildRequest(
                "spring-boot",
                "4.1.2",
                BuildRequestPolicy.APPLICABILITY,
                hash('1'),
                hash('2'),
                hash('3'),
                null,
                sources,
                includes
        );
    }

    // 저장소 경로 하나의 원본 항목
    private static SourceEntry entry(
            String path,
            DocumentType documentType
    ) {

        String url = RAW_ROOT + path;

        return new SourceEntry(
                CanonicalJson
                        .sha256(url),
                documentType,
                url,
                url,
                url,
                "snap-1",
                hash('a'),
                "raw/spring-boot/4.1.2/" + CanonicalJson
                        .sha256(url)
                        .substring(7) + ".txt"
        );
    }

    // 예시 include의 hash와 raw key만 바꾼 항목
    private static SourceEntry with(
            String rawSha256,
            String rawObjectKey
    ) {

        return new SourceEntry(
                EXAMPLE
                        .documentId(),
                EXAMPLE
                        .documentType(),
                EXAMPLE
                        .canonicalUrl(),
                EXAMPLE
                        .originalUrl(),
                EXAMPLE
                        .finalUrl(),
                EXAMPLE
                        .sourceSnapshotId(),
                rawSha256,
                rawObjectKey
        );
    }

    // sha256: 접두와 같은 문자 64개
    private static String hash(char value) {

        return "sha256:" + String
                .valueOf(value)
                .repeat(64);
    }
}
