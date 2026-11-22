package io.github.kangwooju.doclens.pipeline.parse.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabSelectors;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;
import io.github.kangwooju.doclens.pipeline.parse.type.DefaultTabRule;
import io.github.kangwooju.doclens.pipeline.parse.type.VersionMarkerRule;

/** 등록된 파서 프로필 목록과 문서에 맞는 프로필 조회 */
@Component
public class ParserProfileStore {

    /**
     * Spring Boot Antora Reference 프로필, 선택자는 2026-11-20 실제 페이지 조사 결과
     * 실제 표본 15쪽(4.2.0 14쪽, 3.5.16 1쪽)과 대조해 사용자 승인, fixtureHashes는 그 표본의 원본 hash
     * 서버 표식 없는 Properties/YAML 탭은 YAML이 기본
     */
    public static final ParserProfile SPRING_BOOT_REFERENCE = new ParserProfile(
            "spring-antora-reference",
            "2",
            "spring-boot",
            DocumentType.REFERENCE,
            "https://docs\\.spring\\.io/spring-boot/(\\d+\\.\\d+(\\.\\d+)?/)?reference/.+\\.html",
            "article.doc",
            List
                    .of(
                            "div.breadcrumbs-container",
                            "div.admonitionblock.latest",
                            "div.section-summary",
                            "nav.pagination",
                            "p.page-author"
                    ),
            List
                    .of(
                            "h1#page-title",
                            "div.sect1 > h2",
                            "div.sect2 > h3",
                            "div.sect3 > h4",
                            "div.sect4 > h5",
                            "div.sect5 > h6"
                    ),
            new TabSelectors(
                    "div.openblock.tabs",
                    "li.tab"
            ),
            VersionMarkerRule.ANTORA_META_VERSION,
            List
                    .of(
                            "sha256:2a8f03465a046b34f6cc85b5f29f787a3777332113cbaa37c4c9d37587834fe3",
                            "sha256:cef846b83c4ba66d4f3712755e930d1bdc4d98b20f055b62159a0a0db87e85a3",
                            "sha256:99a52885a37288028d9c458a36b4064f3b2eb7352491a3ec778866cf36af1b6d",
                            "sha256:3b678758e11413663e539bf752dada845d5a621054d11c22fb51a470281d8ca3",
                            "sha256:1964b1b68107c0fc35ff2ec8fe56f488932f770edcdffacf576634034d7863bc",
                            "sha256:0bb5f5cca0859effbaa2ff2926c205166b166c66b13d7e3c5958420e528cd9f4",
                            "sha256:8979a91d13321894ccc2e0ab40959f412cd06b34cfc3fe731b185da8cc848bd6",
                            "sha256:c287fb96c41c199615be8679b6914ab77a5fd23d0f0fc02ac673d54099a16c79",
                            "sha256:4a375fac2790036d970f22e03cdaf5b71ef91afaabd71b24abc525d2179cbab4",
                            "sha256:aa91aad82739cb304e288769cf1dc66c415c06209ecd0592ac1ba7822fd8a3d6",
                            "sha256:4b944406cb1776e03a68da42b56ce01292827b54d927c4763f585ba125b154f1",
                            "sha256:7cb849dc385ad15053f26dfe2b9b69dc0cacfdf93336553e1418d360183711ee",
                            "sha256:3cf0976f9b2772685568fba5b2454fed77ae7180d5f6a867421fffaf058512c8",
                            "sha256:71139a8c9a7109b328d16edb6c05415cedbcadf85b2fa4622a68c2f16180b203",
                            "sha256:1d1e5c44673a43e4ab1ec702a7244c2331074dcf138f0e124f0682582d203ac1"
                    ),
            ApprovalState.ENABLED,
            DefaultTabRule.FIRST_IN_SOURCE_ORDER,
            List
                    .of("yaml"),
            Map
                    .of(
                            "Java",
                            "java",
                            "Kotlin",
                            "kotlin",
                            "Groovy",
                            "groovy",
                            "Maven",
                            "maven",
                            "Gradle",
                            "gradle",
                            "Properties",
                            "properties",
                            "YAML",
                            "yaml"
                    ),
            Map
                    .of(
                            VariantGroupType.LANGUAGE,
                            Set
                                    .of(
                                            "java",
                                            "kotlin",
                                            "groovy"
                                    ),
                            VariantGroupType.BUILD_TOOL,
                            Set
                                    .of(
                                            "maven",
                                            "gradle"
                                    ),
                            VariantGroupType.CONFIG_STYLE,
                            Set
                                    .of(
                                            "properties",
                                            "yaml"
                                    )
                    )
    );

    private final List<ParserProfile> profiles;

    /** 기본 등록 프로필 */
    public ParserProfileStore() {

        this(List
                .of(SPRING_BOOT_REFERENCE));
    }

    /** 지정한 프로필 목록 */
    public ParserProfileStore(List<ParserProfile> profiles) {

        this.profiles = List
                .copyOf(profiles);
    }

    /** 등록한 프로필 전체, 등록 순서 그대로 */
    public List<ParserProfile> profiles() {

        return profiles;
    }

    /** project, 문서 유형, 최종 URL이 모두 맞는 첫 프로필, 승인 상태는 보지 않음 */
    public Optional<ParserProfile> find(
            String project,
            DocumentType documentType,
            String finalUrl
    ) {

        return profiles
                .stream()
                .filter(profile -> profile
                        .project()
                        .equals(project))
                .filter(profile -> profile
                        .documentType() == documentType)
                .filter(profile -> Pattern
                        .compile(profile
                                .urlPattern())
                        .matcher(finalUrl)
                        .matches())
                .findFirst();
    }
}
