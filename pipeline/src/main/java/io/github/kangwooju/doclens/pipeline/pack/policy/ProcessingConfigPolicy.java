package io.github.kangwooju.doclens.pipeline.pack.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfigInputs;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;

/**
 * 결과에 영향을 주는 설정의 preimage와 처리 설정 hash
 * Map, List, String, Integer만으로 조립하고 enum은 이름, Set은 code point 정렬 목록, 승인 증거 fixtureHashes는 제외
 */
@Component
public class ProcessingConfigPolicy {

    /** preimage 형식 이름 */
    public static final String KIND = "processing-config-v1";

    /** Pack 메타 출처 표기, Pack 논리 내용이라 preimage에 포함 */
    public static final String SOURCE_ATTRIBUTION = "Spring documentation, Apache-2.0";

    /** Pack 메타 생성자, 버전을 넣지 않아 다른 프로세스가 내보내도 같은 값 */
    public static final String CREATED_BY = "doclens-pipeline";

    /** 입력값의 canonical preimage와 그 SHA-256 */
    public ProcessingConfig create(ProcessingConfigInputs inputs) {

        String preimage = CanonicalJson
                .write(preimage(inputs));

        return new ProcessingConfig(
                CanonicalJson
                        .sha256(preimage),
                preimage,
                inputs
                        .parserPolicyVersion(),
                inputs
                        .chunkerPolicyVersion()
        );
    }

    /** 요청 hash가 설정 hash와 같은지 여부 */
    public boolean matches(
            String requestedHash,
            ProcessingConfig config
    ) {

        return config
                .hash()
                .equals(requestedHash);
    }

    // 입력값 전체의 preimage 객체
    private static Map<String, Object> preimage(ProcessingConfigInputs inputs) {

        Map<String, Object> artifacts = new HashMap<>();
        artifacts
                .put(
                        "structuredDocumentSchemaVersion",
                        inputs
                                .structuredDocumentSchemaVersion()
                );

        Map<String, Object> chunker = new HashMap<>();
        chunker
                .put(
                        "policyVersion",
                        inputs
                                .chunkerPolicyVersion()
                );
        chunker
                .put(
                        "targetBytes",
                        inputs
                                .targetBytes()
                );
        chunker
                .put(
                        "maxBytes",
                        inputs
                                .maxBytes()
                );
        chunker
                .put(
                        "maxBlocks",
                        inputs
                                .maxBlocks()
                );
        chunker
                .put(
                        "overlapMaxBytes",
                        inputs
                                .overlapMaxBytes()
                );
        chunker
                .put(
                        "headerRepeatMaxBytes",
                        inputs
                                .headerRepeatMaxBytes()
                );
        chunker
                .put(
                        "separator",
                        inputs
                                .separator()
                );

        Map<String, Object> identifiers = new HashMap<>();
        identifiers
                .put(
                        "codePatterns",
                        inputs
                                .identifierPatterns()
                );
        identifiers
                .put(
                        "maxLength",
                        inputs
                                .identifierMaxLength()
                );

        Map<String, Object> pack = new HashMap<>();
        pack
                .put(
                        "sourceAttribution",
                        inputs
                                .sourceAttribution()
                );
        pack
                .put(
                        "createdBy",
                        inputs
                                .createdBy()
                );

        Map<String, Object> parser = new HashMap<>();
        parser
                .put(
                        "policyVersion",
                        inputs
                                .parserPolicyVersion()
                );
        parser
                .put(
                        "jsoupVersion",
                        inputs
                                .jsoupVersion()
                );
        parser
                .put(
                        "codeMaxBytes",
                        inputs
                                .codeMaxBytes()
                );
        parser
                .put(
                        "profiles",
                        inputs
                                .profiles()
                                .stream()
                                .sorted(Comparator
                                        .comparing(
                                                ParserProfile::profileId,
                                                CanonicalJson::compareCodePoints
                                        )
                                        .thenComparing(
                                                ParserProfile::profileVersion,
                                                CanonicalJson::compareCodePoints
                                        ))
                                .map(ProcessingConfigPolicy::profile)
                                .toList()
                );

        Map<String, Object> root = new HashMap<>();
        root
                .put(
                        "kind",
                        KIND
                );
        root
                .put(
                        "artifacts",
                        artifacts
                );
        root
                .put(
                        "chunker",
                        chunker
                );
        root
                .put(
                        "identifiers",
                        identifiers
                );
        root
                .put(
                        "pack",
                        pack
                );
        root
                .put(
                        "parser",
                        parser
                );

        return root;
    }

    // 프로필 하나의 preimage, fixtureHashes 제외
    private static Map<String, Object> profile(ParserProfile profile) {

        Map<String, Object> tabSelectors = new HashMap<>();
        tabSelectors
                .put(
                        "group",
                        profile
                                .tabSelectors()
                                .group()
                );
        tabSelectors
                .put(
                        "tab",
                        profile
                                .tabSelectors()
                                .tab()
                );

        Map<String, Object> groupTypes = new HashMap<>();

        for (Map.Entry<VariantGroupType, Set<String>> entry : profile
                .groupTypes()
                .entrySet()) {
            groupTypes
                    .put(
                            entry
                                    .getKey()
                                    .name(),
                            sorted(entry
                                    .getValue())
                    );
        }

        Map<String, Object> map = new HashMap<>();
        map
                .put(
                        "profileId",
                        profile
                                .profileId()
                );
        map
                .put(
                        "profileVersion",
                        profile
                                .profileVersion()
                );
        map
                .put(
                        "project",
                        profile
                                .project()
                );
        map
                .put(
                        "documentType",
                        profile
                                .documentType()
                                .name()
                );
        map
                .put(
                        "urlPattern",
                        profile
                                .urlPattern()
                );
        map
                .put(
                        "rootSelector",
                        profile
                                .rootSelector()
                );
        map
                .put(
                        "excludedSelectors",
                        profile
                                .excludedSelectors()
                );
        map
                .put(
                        "headingSelectors",
                        profile
                                .headingSelectors()
                );
        map
                .put(
                        "tabSelectors",
                        tabSelectors
                );
        map
                .put(
                        "versionMarkerRule",
                        profile
                                .versionMarkerRule()
                                .name()
                );
        map
                .put(
                        "approvalState",
                        profile
                                .approvalState()
                                .name()
                );
        map
                .put(
                        "defaultTabRule",
                        profile
                                .defaultTabRule()
                                .name()
                );
        map
                .put(
                        "preferredDefaultLabels",
                        profile
                                .preferredDefaultLabels()
                );
        map
                .put(
                        "canonicalLabels",
                        new HashMap<String, Object>(profile
                                .canonicalLabels())
                );
        map
                .put(
                        "groupTypes",
                        groupTypes
                );

        return map;
    }

    // 집합을 code point 순 목록으로
    private static List<String> sorted(Set<String> values) {

        List<String> list = new ArrayList<>(values);
        list
                .sort(CanonicalJson::compareCodePoints);

        return list;
    }
}
