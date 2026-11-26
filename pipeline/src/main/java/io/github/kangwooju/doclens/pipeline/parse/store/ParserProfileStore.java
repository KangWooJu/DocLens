package io.github.kangwooju.doclens.pipeline.parse.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocRules;
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
                    ),
            null
    );

    /**
     * Spring Boot GitHub AsciiDoc Reference 프로필, 같은 commit의 include를 써서 HTML로 바꾼 뒤 HTML 프로필과 같은 선택자로 읽음
     * 버전 근거는 문서 URL의 commit
     * 실제 표본 24개(4.1.3 12쪽, 4.1.2 12쪽)를 같은 판 live 쪽과 대조하고 독립 검사 뒤 사용자 승인
     * fixtureHashes는 그 표본의 원본 hash, 두 판 bytes가 같은 8쪽은 한 번만
     * 생성 partial은 원본에 없어 표지 한 줄, 잔여 허용 항목은 원문 저자 실수 한 곳
     */
    public static final ParserProfile SPRING_BOOT_ASCIIDOC_REFERENCE = new ParserProfile(
            "spring-boot-asciidoc-reference",
            "1",
            "spring-boot",
            DocumentType.REFERENCE,
            "https://raw\\.githubusercontent\\.com/spring-projects/spring-boot/[0-9a-f]{40}/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/.+\\.adoc",
            "article.doc",
            List
                    .of(),
            SPRING_BOOT_REFERENCE
                    .headingSelectors(),
            SPRING_BOOT_REFERENCE
                    .tabSelectors(),
            VersionMarkerRule.COMMIT_PINNED_URL,
            List
                    .of(
                            "sha256:bb4e0aabdc6e19dd086ab3a51234bb11af6c8385cbbe87fcab680f2d95757939",
                            "sha256:9af75f39400ac618e66086670e5f44f57afcec242f43b8a302549aae37edd7c6",
                            "sha256:977cf3fc0a2ad3f9493ec67e854acbeebb92211bcec4e964c858b6613d7b1e5a",
                            "sha256:29ad818a12e042a013607e76055919c5c2c41a32aec5d806faa478bf378d9050",
                            "sha256:b856d4bf173c42208c29e51d05de77507e54ed6b9be4bd3ee808dd76f0e5b1e5",
                            "sha256:474ce651b7c052990c481309eae4c2b5b800d8f0f7db022c0171af7e2c1b7b4e",
                            "sha256:04dfd63e9da52f4f74913158e3b1cd31ca6c634ad7deffd465555d336b9e878a",
                            "sha256:d95db9e5f6e47ad5489a088d5a40a8e762b8506d8a1c52b26d6b3d60f5b23d0b",
                            "sha256:90ba4998126faef910f5cf0b46141c6d34c6764b8520a1d0284f459a51f9874a",
                            "sha256:4f883a716affc8098eb4d5539e5dbdfe37e71a7d463823dcb5a400b89c6a74e6",
                            "sha256:6880a855e84ce515ef692d626b629f82d6373d4cbaf0e732ca3217b6982b1da3",
                            "sha256:651902e5735ce77f32d24808ae7ee95dbb90ca99780db0096852231a492c0028",
                            "sha256:c29a4e1d7a946d6dce7e7770ce848bbb3ff0649221dddfcab7f0ea743d2b0ee0",
                            "sha256:c2abfb0e59e211f73b989cf96ea0f9a91fca11af1d16b8eccfdffb824c8f6b02",
                            "sha256:815f9fc63af0be0e9737dce1f091bfd74c836eab9cf7f565fad53dbb24d836bc",
                            "sha256:e05598b0083e3c15f850ac52b3655af90334c041b5b72e75a0548e96a951d23e"
                    ),
            ApprovalState.ENABLED,
            SPRING_BOOT_REFERENCE
                    .defaultTabRule(),
            SPRING_BOOT_REFERENCE
                    .preferredDefaultLabels(),
            SPRING_BOOT_REFERENCE
                    .canonicalLabels(),
            SPRING_BOOT_REFERENCE
                    .groupTypes(),
            new AsciidocRules(
                    "https://docs.spring.io/spring-boot/",
                    "https://github.com/spring-projects/spring-boot/blob/",
                    "documentation/spring-boot-docs/src/docs/antora/modules/",
                    "documentation/spring-boot-docs/src/main/",
                    "documentation/spring-boot-docs/src/main/{lang}/org/springframework/boot/docs/",
                    List
                            .of(
                                    "ROOT:partial$starters/",
                                    "ROOT:partial$propertydefaults/",
                                    "ROOT:partial$application/",
                                    "ROOT:partial$logging/",
                                    "ROOT:example$remote-spring-application.txt"
                            ),
                    List
                            .of(
                                    "url-",
                                    "code-",
                                    "apiref-"
                            ),
                    List
                            .of("web/spring-graphql.adoc|include-code::RSocketGraphQlClientExample["),
                    Map
                            .of(
                                    "build-type",
                                    "opensource",
                                    "hide-uri-scheme",
                                    ""
                            ),
                    "version-spring-boot"
            )
    );

    private final List<ParserProfile> profiles;

    /** 기본 등록 프로필 */
    public ParserProfileStore() {

        this(List
                .of(
                        SPRING_BOOT_REFERENCE,
                        SPRING_BOOT_ASCIIDOC_REFERENCE
                ));
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
