package io.github.kangwooju.doclens.pipeline.pack.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfigInputs;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocRules;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;

@DisplayName("처리 설정 preimage와 hash")
class ProcessingConfigPolicyTests {

    private static final ParserProfile PROFILE = ParserProfileStore.SPRING_BOOT_REFERENCE;

    private final ProcessingConfigPolicy policy = new ProcessingConfigPolicy();

    @Test
    @DisplayName("대응표와 집합의 순서만 다르면 같은 hash")
    void ignoresMapAndSetOrder() {

        Map<String, String> labels = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(PROFILE
                .canonicalLabels()
                .keySet());

        keys
                .sort(null);

        for (String key : keys
                .reversed()) {
            labels
                    .put(
                            key,
                            PROFILE
                                    .canonicalLabels()
                                    .get(key)
                    );
        }

        Map<VariantGroupType, Set<String>> groups = new LinkedHashMap<>();

        for (Map.Entry<VariantGroupType, Set<String>> entry : PROFILE
                .groupTypes()
                .entrySet()) {
            List<String> values = new ArrayList<>(entry
                    .getValue());

            values
                    .sort(null);
            groups
                    .put(
                            entry
                                    .getKey(),
                            new LinkedHashSet<>(values
                                    .reversed())
                    );
        }

        assertEquals(
                hash(inputs(PROFILE)),
                hash(inputs(copy(
                        PROFILE,
                        PROFILE
                                .rootSelector(),
                        PROFILE
                                .fixtureHashes(),
                        labels,
                        groups
                )))
        );
    }

    @Test
    @DisplayName("선택자, 청크 상한, 식별자 정규식, jsoup 버전이 바뀌면 다른 hash")
    void changesWithBehaviorInputs() {

        String base = hash(inputs(PROFILE));
        ProcessingConfigInputs original = inputs(PROFILE);

        assertNotEquals(
                base,
                hash(inputs(copy(
                        PROFILE,
                        "article.page",
                        PROFILE
                                .fixtureHashes(),
                        PROFILE
                                .canonicalLabels(),
                        PROFILE
                                .groupTypes()
                )))
        );
        assertNotEquals(
                base,
                hash(new ProcessingConfigInputs(
                        original
                                .parserPolicyVersion(),
                        original
                                .chunkerPolicyVersion(),
                        original
                                .jsoupVersion(),
                        original
                                .asciidoctorjVersion(),
                        original
                                .jrubyVersion(),
                        original
                                .structuredDocumentSchemaVersion(),
                        original
                                .codeMaxBytes(),
                        original
                                .targetBytes() + 1,
                        original
                                .maxBytes(),
                        original
                                .maxBlocks(),
                        original
                                .overlapMaxBytes(),
                        original
                                .headerRepeatMaxBytes(),
                        original
                                .separator(),
                        original
                                .identifierPatterns(),
                        original
                                .identifierMaxLength(),
                        original
                                .sourceAttribution(),
                        original
                                .createdBy(),
                        original
                                .profiles()
                ))
        );
        assertNotEquals(
                base,
                hash(new ProcessingConfigInputs(
                        original
                                .parserPolicyVersion(),
                        original
                                .chunkerPolicyVersion(),
                        original
                                .jsoupVersion(),
                        original
                                .asciidoctorjVersion(),
                        original
                                .jrubyVersion(),
                        original
                                .structuredDocumentSchemaVersion(),
                        original
                                .codeMaxBytes(),
                        original
                                .targetBytes(),
                        original
                                .maxBytes(),
                        original
                                .maxBlocks(),
                        original
                                .overlapMaxBytes(),
                        original
                                .headerRepeatMaxBytes(),
                        original
                                .separator(),
                        List
                                .of("(?<![\\w.])[A-Z]\\w+"),
                        original
                                .identifierMaxLength(),
                        original
                                .sourceAttribution(),
                        original
                                .createdBy(),
                        original
                                .profiles()
                ))
        );
        assertNotEquals(
                base,
                hash(new ProcessingConfigInputs(
                        original
                                .parserPolicyVersion(),
                        original
                                .chunkerPolicyVersion(),
                        "1.25.0",
                        original
                                .asciidoctorjVersion(),
                        original
                                .jrubyVersion(),
                        original
                                .structuredDocumentSchemaVersion(),
                        original
                                .codeMaxBytes(),
                        original
                                .targetBytes(),
                        original
                                .maxBytes(),
                        original
                                .maxBlocks(),
                        original
                                .overlapMaxBytes(),
                        original
                                .headerRepeatMaxBytes(),
                        original
                                .separator(),
                        original
                                .identifierPatterns(),
                        original
                                .identifierMaxLength(),
                        original
                                .sourceAttribution(),
                        original
                                .createdBy(),
                        original
                                .profiles()
                ))
        );
    }

    @Test
    @DisplayName("AsciidoctorJ, JRuby 버전, AsciiDoc 규칙, 구조 산출물 형식 버전이 바뀌면 다른 hash")
    void changesWithAsciidocInputs() {

        ParserProfile asciidoc = ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE;
        ProcessingConfigInputs original = inputs(asciidoc);
        String base = hash(original);
        AsciidocRules rules = asciidoc
                .asciidoc();
        AsciidocRules otherRules = new AsciidocRules(
                rules
                        .readerUrlRoot(),
                rules
                        .sourceViewRoot(),
                rules
                        .antoraModulesPath(),
                rules
                        .rootExamplesPath(),
                rules
                        .includeCodePath(),
                rules
                        .generatedIncludes(),
                rules
                        .linkAttributePrefixes(),
                List
                        .of(),
                rules
                        .attributes(),
                rules
                        .exactVersionAttribute()
        );
        String preimage = policy
                .create(original)
                .preimageJson();

        for (String key : List
                .of(
                        "\"asciidoctorjVersion\":\"3.0.1\"",
                        "\"jrubyVersion\":\"9.4.14.0\"",
                        "\"structuredDocumentSchemaVersion\":2",
                        "\"exactVersionAttribute\":\"version-spring-boot\"",
                        "\"residueAllowlist\":[\"web/spring-graphql.adoc|include-code::RSocketGraphQlClientExample[\"]"
                )) {
            assertTrue(
                    preimage
                            .contains(key),
                    key
            );
        }

        assertTrue(policy
                .create(inputs(PROFILE))
                .preimageJson()
                .contains("\"asciidoc\":null"));
        assertNotEquals(
                base,
                hash(versions(
                        original,
                        "3.0.2",
                        original
                                .jrubyVersion(),
                        original
                                .structuredDocumentSchemaVersion()
                ))
        );
        assertNotEquals(
                base,
                hash(versions(
                        original,
                        original
                                .asciidoctorjVersion(),
                        "9.4.15.0",
                        original
                                .structuredDocumentSchemaVersion()
                ))
        );
        assertNotEquals(
                base,
                hash(versions(
                        original,
                        original
                                .asciidoctorjVersion(),
                        original
                                .jrubyVersion(),
                        1
                ))
        );
        assertNotEquals(
                base,
                hash(inputs(new ParserProfile(
                        asciidoc
                                .profileId(),
                        asciidoc
                                .profileVersion(),
                        asciidoc
                                .project(),
                        asciidoc
                                .documentType(),
                        asciidoc
                                .urlPattern(),
                        asciidoc
                                .rootSelector(),
                        asciidoc
                                .excludedSelectors(),
                        asciidoc
                                .headingSelectors(),
                        asciidoc
                                .tabSelectors(),
                        asciidoc
                                .versionMarkerRule(),
                        asciidoc
                                .fixtureHashes(),
                        asciidoc
                                .approvalState(),
                        asciidoc
                                .defaultTabRule(),
                        asciidoc
                                .preferredDefaultLabels(),
                        asciidoc
                                .canonicalLabels(),
                        asciidoc
                                .groupTypes(),
                        otherRules
                )))
        );
    }

    @Test
    @DisplayName("승인 증거 fixtureHashes만 바뀌면 같은 hash")
    void ignoresFixtureHashes() {

        assertEquals(
                hash(inputs(PROFILE)),
                hash(inputs(copy(
                        PROFILE,
                        PROFILE
                                .rootSelector(),
                        List
                                .of(),
                        PROFILE
                                .canonicalLabels(),
                        PROFILE
                                .groupTypes()
                )))
        );
    }

    @Test
    @DisplayName("preimage는 canonical JSON이고 hash는 그 SHA-256")
    void hashesCanonicalPreimage() {

        ProcessingConfig config = policy
                .create(inputs(PROFILE));

        assertEquals(
                CanonicalJson
                        .canonicalize(config
                                .preimageJson()),
                config
                        .preimageJson()
        );
        assertEquals(
                CanonicalJson
                        .sha256(config
                                .preimageJson()),
                config
                        .hash()
        );
    }

    // 입력값의 hash
    private String hash(ProcessingConfigInputs inputs) {

        return policy
                .create(inputs)
                .hash();
    }

    // 라이브러리 버전과 구조 산출물 형식 버전만 바꾼 입력
    private static ProcessingConfigInputs versions(
            ProcessingConfigInputs original,
            String asciidoctorjVersion,
            String jrubyVersion,
            int structuredDocumentSchemaVersion
    ) {

        return new ProcessingConfigInputs(
                original
                        .parserPolicyVersion(),
                original
                        .chunkerPolicyVersion(),
                original
                        .jsoupVersion(),
                asciidoctorjVersion,
                jrubyVersion,
                structuredDocumentSchemaVersion,
                original
                        .codeMaxBytes(),
                original
                        .targetBytes(),
                original
                        .maxBytes(),
                original
                        .maxBlocks(),
                original
                        .overlapMaxBytes(),
                original
                        .headerRepeatMaxBytes(),
                original
                        .separator(),
                original
                        .identifierPatterns(),
                original
                        .identifierMaxLength(),
                original
                        .sourceAttribution(),
                original
                        .createdBy(),
                original
                        .profiles()
        );
    }

    // 프로필 하나와 고정 값으로 만든 입력
    private static ProcessingConfigInputs inputs(ParserProfile profile) {

        return new ProcessingConfigInputs(
                "parser-v4",
                "chunker-v3-jdk21",
                "1.24.1",
                "3.0.1",
                "9.4.14.0",
                2,
                1048576,
                6000,
                12000,
                32,
                800,
                3000,
                "\n\n",
                List
                        .of("(?<![\\w.@{])@[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*"),
                256,
                ProcessingConfigPolicy.SOURCE_ATTRIBUTION,
                ProcessingConfigPolicy.CREATED_BY,
                List
                        .of(profile)
        );
    }

    // 일부 값만 바꾼 프로필 사본
    private static ParserProfile copy(
            ParserProfile profile,
            String rootSelector,
            List<String> fixtureHashes,
            Map<String, String> canonicalLabels,
            Map<VariantGroupType, Set<String>> groupTypes
    ) {

        return new ParserProfile(
                profile
                        .profileId(),
                profile
                        .profileVersion(),
                profile
                        .project(),
                profile
                        .documentType(),
                profile
                        .urlPattern(),
                rootSelector,
                profile
                        .excludedSelectors(),
                profile
                        .headingSelectors(),
                profile
                        .tabSelectors(),
                profile
                        .versionMarkerRule(),
                fixtureHashes,
                profile
                        .approvalState(),
                profile
                        .defaultTabRule(),
                profile
                        .preferredDefaultLabels(),
                canonicalLabels,
                groupTypes,
                profile
                        .asciidoc()
        );
    }
}
