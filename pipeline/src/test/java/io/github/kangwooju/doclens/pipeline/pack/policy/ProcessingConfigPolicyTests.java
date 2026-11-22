package io.github.kangwooju.doclens.pipeline.pack.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    // 프로필 하나와 고정 값으로 만든 입력
    private static ProcessingConfigInputs inputs(ParserProfile profile) {

        return new ProcessingConfigInputs(
                "parser-v3",
                "chunker-v2-jdk21",
                "1.24.1",
                1,
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
                groupTypes
        );
    }
}
