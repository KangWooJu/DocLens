package io.github.kangwooju.doclens.pipeline.parse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.converter.StructuredDocumentConverter;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocRules;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.ResolvedInclude;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredSection;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TitleSource;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.helper.AsciidocHtmlHelper;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

@DisplayName("AsciiDoc 원본의 구조 산출물 변환")
class AsciidocParseServiceTests {

    private static final DocumentParseService SERVICE = ParseFixtures
            .asciidocService();

    private static final Map<String, SourceDocument> INCLUDES = ParseFixtures
            .asciidocIncludes();

    private static final String EXAMPLES = "documentation/spring-boot-docs/src/main/";

    private static final String SOURCE_VIEW = "https://github.com/spring-projects/spring-boot/blob/0123456789abcdef0123456789abcdef01234567/";

    @Test
    @DisplayName("섹션 id, level, heading 경로와 문단, admonition, 표")
    void buildsSectionsAndBlocks() {

        StructuredDocument document = parse("sample.adoc");
        List<StructuredSection> sections = document
                .sections();

        assertEquals(
                "Sample Guide",
                document
                        .title()
        );
        assertEquals(
                List
                        .of(
                                "page-title",
                                "sample.basics",
                                "sample.basics.table",
                                "sample.basics.table.detail",
                                "sample.code",
                                "sample.code.java-only",
                                "sample.code.tagged",
                                "sample.config",
                                "sample.generated"
                        ),
                sections
                        .stream()
                        .map(StructuredSection::anchor)
                        .toList()
        );
        assertEquals(
                List
                        .of(
                                1,
                                2,
                                3,
                                4,
                                2,
                                3,
                                3,
                                2,
                                2
                        ),
                sections
                        .stream()
                        .map(StructuredSection::level)
                        .toList()
        );
        assertEquals(
                List
                        .of(
                                "Sample Guide",
                                "Basics",
                                "Table",
                                "Detail"
                        ),
                section(
                        document,
                        "sample.basics.table.detail"
                )
                        .headingPath()
        );

        List<Block> basics = section(
                document,
                "sample.basics"
        )
                .blocks();

        assertEquals(
                List
                        .of(
                                "note",
                                "warning"
                        ),
                basics
                        .stream()
                        .filter(block -> block
                                .kind() == BlockKind.ADMONITION)
                        .map(Block::admonitionType)
                        .toList()
        );
        assertEquals(
                3,
                section(
                        document,
                        "sample.basics.table"
                )
                        .blocks()
                        .getFirst()
                        .table()
                        .rows()
                        .size()
        );
    }

    @Test
    @DisplayName("include-code는 예시 파일 bytes 그대로, 두 언어면 Java 기본 탭, 하나면 코드 블록, 태그면 거른 결과")
    void includesExampleCode() {

        StructuredDocument document = parse("sample.adoc");
        TabGroup group = section(
                document,
                "sample.code"
        )
                .blocks()
                .getFirst()
                .tabGroup();
        Block javaOnly = section(
                document,
                "sample.code.java-only"
        )
                .blocks()
                .getFirst();
        Block tagged = section(
                document,
                "sample.code.tagged"
        )
                .blocks()
                .getFirst();

        assertEquals(
                VariantGroupType.LANGUAGE,
                group
                        .groupType()
        );
        assertEquals(
                List
                        .of(
                                "Java",
                                "Kotlin"
                        ),
                group
                        .variants()
                        .stream()
                        .map(variant -> variant
                                .label())
                        .toList()
        );
        assertEquals(
                group
                        .variants()
                        .getFirst()
                        .variantKey(),
                group
                        .defaultVariant()
        );
        assertEquals(
                example("java/org/springframework/boot/docs/sample/code/GreetingService.java"),
                group
                        .variants()
                        .getFirst()
                        .blocks()
                        .getFirst()
                        .text()
        );
        assertEquals(
                example("kotlin/org/springframework/boot/docs/sample/code/GreetingService.kt"),
                group
                        .variants()
                        .get(1)
                        .blocks()
                        .getFirst()
                        .text()
        );
        assertEquals(
                BlockKind.CODE,
                javaOnly
                        .kind()
        );
        assertEquals(
                "java",
                javaOnly
                        .language()
        );
        assertEquals(
                example("java/org/springframework/boot/docs/sample/code/javaonly/JavaOnly.java"),
                javaOnly
                        .text()
        );
        assertEquals(
                "\t/** 핵심 값 */\n\tpublic String core() {\n\t\treturn \"core\";\n\t}\n",
                tagged
                        .text()
        );
    }

    @Test
    @DisplayName("configprops는 YAML 코드, 명시 탭은 Maven 기본, 속성 넣은 코드는 정확한 버전, 코드 안 잔여 모양은 통과")
    void convertsConfigurationAndTabs() {

        List<Block> blocks = section(
                parse("sample.adoc"),
                "sample.config"
        )
                .blocks();
        TabGroup tabs = blocks
                .get(1)
                .tabGroup();

        assertEquals(
                "yaml",
                blocks
                        .getFirst()
                        .language()
        );
        assertEquals(
                VariantGroupType.BUILD_TOOL,
                tabs
                        .groupType()
        );
        assertEquals(
                List
                        .of(
                                "maven",
                                "gradle"
                        ),
                tabs
                        .variants()
                        .stream()
                        .map(variant -> variant
                                .canonicalLabel())
                        .toList()
        );
        assertEquals(
                tabs
                        .variants()
                        .getFirst()
                        .variantKey(),
                tabs
                        .defaultVariant()
        );
        assertEquals(
                "sample-version=4.1.2\nliteral {x} and foo::bar[] stay in code",
                blocks
                        .get(2)
                        .text()
        );
    }

    @Test
    @DisplayName("같은 module 링크는 commit 고정 원본 URL, 다른 module과 링크 속성은 글만, 맨 링크 속성은 속성 글자")
    void resolvesLinks() {

        StructuredDocument document = parse("sample.adoc");
        Block basics = section(
                document,
                "sample.basics"
        )
                .blocks()
                .getFirst();
        Block detail = section(
                document,
                "sample.basics.table.detail"
        )
                .blocks()
                .getFirst();

        assertEquals(
                List
                        .of(
                                ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "sample.adoc",
                                ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "nested/deep.adoc"
                        ),
                basics
                        .links()
                        .stream()
                        .map(BlockLink::targetUrl)
                        .toList()
        );
        assertTrue(basics
                .text()
                .endsWith("How-to Guides and api:java/index.adoc."));
        assertTrue(detail
                .links()
                .isEmpty());
        assertTrue(detail
                .text()
                .endsWith("Read the sample site or open {url-sample-home}."));
        assertEquals(
                ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "sample.adoc",
                section(
                        parse("nested/deep.adoc"),
                        "deep.intro"
                )
                        .blocks()
                        .getFirst()
                        .links()
                        .getFirst()
                        .targetUrl()
        );
    }

    @Test
    @DisplayName("javadoc 넷과 configprop 둘은 링크 없는 인라인 코드, 생성 partial은 표지 문단, partial include는 본문")
    void rendersInlineMacrosAndIncludes() {

        StructuredDocument document = parse("sample.adoc");
        List<Block> generated = section(
                document,
                "sample.generated"
        )
                .blocks();

        assertEquals(
                List
                        .of(
                                "SampleClient",
                                "SampleClient.send(String, int)",
                                "@SampleBean",
                                "Inner Type",
                                "sample.client.base-url",
                                "SAMPLE_CLIENT_BASEURL"
                        ),
                section(
                        document,
                        "sample.basics.table.detail"
                )
                        .blocks()
                        .getFirst()
                        .inlineCodes()
                        .stream()
                        .map(InlineCode::text)
                        .toList()
        );
        assertEquals(
                "[DocLens] generated content not in source: ROOT:partial$starters/sample-starters.adoc",
                generated
                        .getFirst()
                        .text()
        );
        assertEquals(
                "This snippet comes from a partial file.",
                generated
                        .get(1)
                        .text()
        );
    }

    @Test
    @DisplayName("섹션 인용은 major.minor 읽기 URL과 원본 id 줄, 제목 섹션은 제목 줄, 깊이 2 쪽도 같은 규칙")
    void citesSections() {

        StructuredDocument sample = parse("sample.adoc");
        StructuredDocument deep = parse("nested/deep.adoc");

        assertEquals(
                "https://docs.spring.io/spring-boot/4.1/reference/sample.html#sample.code",
                section(
                        sample,
                        "sample.code"
                )
                        .citation()
                        .readerUrl()
        );
        assertEquals(
                SOURCE_VIEW + ParseFixtures.PAGES + "sample.adoc?plain=1#L39",
                section(
                        sample,
                        "sample.code"
                )
                        .citation()
                        .sourceUrl()
        );
        assertEquals(
                SOURCE_VIEW + ParseFixtures.PAGES + "sample.adoc?plain=1#L2",
                sample
                        .sections()
                        .getFirst()
                        .citation()
                        .sourceUrl()
        );
        assertEquals(
                "https://docs.spring.io/spring-boot/4.1/reference/nested/deep.html#deep.intro",
                section(
                        deep,
                        "deep.intro"
                )
                        .citation()
                        .readerUrl()
        );
        assertEquals(
                SOURCE_VIEW + ParseFixtures.PAGES + "nested/deep.adoc?plain=1#L4",
                section(
                        deep,
                        "deep.intro"
                )
                        .citation()
                        .sourceUrl()
        );
    }

    @Test
    @DisplayName("출처 기록은 쪽이 실제로 쓴 include의 URL, hash, 태그, 버전 표식 없이 commit 고정 URL로 통과")
    void recordsUsedIncludes() {

        StructuredDocument document = parse("sample.adoc");
        List<ResolvedInclude> includes = document
                .provenance()
                .includes();
        String tagged = ParseFixtures.RAW_ROOT + EXAMPLES + "java/org/springframework/boot/docs/sample/code/tagged/Tagged.java";

        assertEquals(
                5,
                includes
                        .size()
        );
        assertEquals(
                new ResolvedInclude(
                        tagged,
                        INCLUDES
                                .get(tagged)
                                .rawSha256(),
                        "core"
                ),
                includes
                        .get(3)
        );
        assertNull(includes
                .getFirst()
                .tags());
        assertEquals(
                "parser-v5",
                document
                        .provenance()
                        .parserPolicyVersion()
        );
        assertEquals(
                3,
                document
                        .schemaVersion()
        );
    }

    @Test
    @DisplayName("같은 module xref의 빈 글은 같은 Build 쪽의 섹션 제목이나 쪽 제목, 없는 쪽은 경로 글 그대로 세고 제목 준 쪽을 기록")
    void titlesEmptyXrefs() {

        SourceDocument page = ParseFixtures
                .asciidocPage("titles.adoc");
        SourceDocument deep = ParseFixtures
                .asciidocPage("nested/deep.adoc");
        Map<String, SourceDocument> inputs = withPages();
        StructuredDocument document = SERVICE
                .parse(
                        page,
                        inputs
                );
        Block links = section(
                document,
                "titles.links"
        )
                .blocks()
                .getFirst();

        assertEquals(
                List
                        .of(
                                "Introduction",
                                "Deep Page",
                                "Bare URL Links",
                                "missing.adoc"
                        ),
                links
                        .links()
                        .stream()
                        .map(BlockLink::label)
                        .toList()
        );
        assertEquals(
                List
                        .of(
                                "deep.intro",
                                "",
                                "titles.bare",
                                "missing.part"
                        ),
                links
                        .links()
                        .stream()
                        .map(BlockLink::targetAnchor)
                        .toList()
        );
        assertEquals(
                List
                        .of(new TitleSource(
                                deep
                                        .canonicalUrl(),
                                deep
                                        .rawSha256()
                        )),
                document
                        .provenance()
                        .titleSources()
        );

        Map<String, Integer> counts = ParseFixtures.ASCIIDOC_HTML_HELPER
                .html(
                        page,
                        ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE
                                .asciidoc(),
                        inputs
                )
                .counts();

        assertEquals(
                3,
                counts
                        .get("xrefTitles")
        );
        assertEquals(
                1,
                counts
                        .get("untitledXrefs")
        );
        assertTrue(parse("sample.adoc")
                .provenance()
                .titleSources()
                .isEmpty());
    }

    @Test
    @DisplayName("맨 URL 링크 글은 scheme을 숨기고 링크 주소는 그대로")
    void hidesBareUriScheme() {

        Block bare = section(
                SERVICE
                        .parse(
                                ParseFixtures
                                        .asciidocPage("titles.adoc"),
                                INCLUDES
                        ),
                "titles.bare"
        )
                .blocks()
                .getFirst();

        assertEquals(
                "Visit example.org/guide or localhost:8080/health.",
                bare
                        .text()
        );
        assertEquals(
                List
                        .of(
                                "https://example.org/guide",
                                "http://localhost:8080/health"
                        ),
                bare
                        .links()
                        .stream()
                        .map(BlockLink::href)
                        .toList()
        );
    }

    @Test
    @DisplayName("javadoc 멤버의 가변 인자는 문자 참조 없이 점 셋")
    void keepsJavadocVarargs() {

        Block call = section(
                SERVICE
                        .parse(
                                ParseFixtures
                                        .asciidocPage("titles.adoc"),
                                INCLUDES
                        ),
                "titles.bare"
        )
                .blocks()
                .get(1);

        assertEquals(
                List
                        .of("SampleRunner.run(Class, String...)"),
                call
                        .inlineCodes()
                        .stream()
                        .map(InlineCode::text)
                        .toList()
        );
        assertEquals(
                "Call SampleRunner.run(Class, String...) to start.",
                call
                        .text()
        );
    }

    @Test
    @DisplayName("못 찾은 include-code, 태그, include, 파일과 URI 대상, 모르는 속성, 잔여, 경고는 ASCIIDOC_UNRESOLVED")
    void holdsUnresolvedSources() {

        for (String page : List
                .of(
                        "broken-include-code.adoc",
                        "broken-tag.adoc",
                        "broken-include.adoc",
                        "broken-unsafe.adoc",
                        "broken-include-attribute.adoc",
                        "broken-residue.adoc",
                        "warn-unclosed.adoc"
                )) {
            assertEquals(
                    HoldReason.ASCIIDOC_UNRESOLVED,
                    hold(
                            SERVICE,
                            page,
                            INCLUDES
                    )
                            .reason(),
                    page
            );
        }

        assertTrue(hold(
                SERVICE,
                "broken-unsafe.adoc",
                INCLUDES
        )
                .getMessage()
                .contains("include /etc/hosts not found"));
    }

    @Test
    @DisplayName("기본 등록 AsciiDoc 프로필은 승인 상태라 보류 없이 파싱")
    void defaultProfileIsEnabled() {

        assertEquals(
                ApprovalState.ENABLED,
                ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE
                        .approvalState()
        );
        assertEquals(
                16,
                Set
                        .copyOf(ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE
                                .fixtureHashes())
                        .size()
        );
        assertEquals(
                "Sample Guide",
                ParseFixtures
                        .service(ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE)
                        .parse(
                                ParseFixtures
                                        .asciidocPage("sample.adoc"),
                                INCLUDES
                        )
                        .title()
        );
    }

    @Test
    @DisplayName("같은 잔여가 허용 목록에 있으면 통과, CANDIDATE 프로필은 보류")
    void allowsListedResidueAndHoldsCandidate() {

        AsciidocRules rules = ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE
                .asciidoc();
        AsciidocRules allowing = new AsciidocRules(
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
                        .of(
                                "broken-residue.adoc|foo::bar[",
                                "broken-residue.adoc|{undefined-attr}"
                        ),
                rules
                        .attributes(),
                rules
                        .exactVersionAttribute()
        );

        assertTrue(ParseFixtures
                .service(withRules(allowing))
                .parse(
                        ParseFixtures
                                .asciidocPage("broken-residue.adoc"),
                        INCLUDES
                )
                .sections()
                .get(1)
                .blocks()
                .getFirst()
                .text()
                .contains("foo::bar[] inside"));
        assertEquals(
                HoldReason.SELECTOR_UNVERIFIED,
                hold(
                        ParseFixtures
                                .service(ParseFixtures
                                        .withApproval(
                                                ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE,
                                                ApprovalState.CANDIDATE
                                        )),
                        "sample.adoc",
                        INCLUDES
                )
                        .reason()
        );
    }

    @Test
    @DisplayName("앞 문서의 include 기록, 경고, 변환 예외가 다음 문서에 섞이지 않음")
    void isolatesCalls() {

        parse("sample.adoc");

        assertTrue(parse("nested/deep.adoc")
                .provenance()
                .includes()
                .isEmpty());

        hold(
                SERVICE,
                "warn-unclosed.adoc",
                INCLUDES
        );

        assertEquals(
                "Deep Page",
                parse("nested/deep.adoc")
                        .title()
        );

        Map<String, SourceDocument> failing = new AbstractMap<>() {

            /** 빈 항목 집합 */
            @Override
            public Set<Map.Entry<String, SourceDocument>> entrySet() {

                return Set
                        .of();
            }

            /** 조회마다 실패 */
            @Override
            public SourceDocument get(Object key) {

                throw new IllegalStateException("storage failure");
            }
        };

        assertTrue(hold(
                SERVICE,
                "sample.adoc",
                failing
        )
                .getMessage()
                .contains("conversion failed: java.lang.IllegalStateException"));
        assertEquals(
                5,
                parse("sample.adoc")
                        .provenance()
                        .includes()
                        .size()
        );
    }

    @Test
    @DisplayName("같은 입력은 같은 JSON, 기본 시간대와 언어를 바꿔도 같음")
    void isDeterministic() {

        StructuredDocumentConverter converter = new StructuredDocumentConverter();
        String first = converter
                .toJson(parse("sample.adoc"));
        TimeZone timeZone = TimeZone
                .getDefault();
        Locale locale = Locale
                .getDefault();

        assertEquals(
                first,
                converter
                        .toJson(parse("sample.adoc"))
        );

        try {
            TimeZone
                    .setDefault(TimeZone
                            .getTimeZone("Pacific/Kiritimati"));
            Locale
                    .setDefault(Locale.FRENCH);

            assertEquals(
                    first,
                    converter
                            .toJson(parse("sample.adoc"))
            );
        } finally {
            TimeZone
                    .setDefault(timeZone);
            Locale
                    .setDefault(locale);
        }
    }

    @Test
    @DisplayName("AsciidoctorJ와 JRuby 버전은 실행 classpath 값")
    void readsLibraryVersions() {

        assertEquals(
                "3.0.1",
                AsciidocHtmlHelper
                        .asciidoctorjVersion()
        );
        assertEquals(
                "9.4.14.0",
                AsciidocHtmlHelper
                        .jrubyVersion()
        );
    }

    // ENABLED AsciiDoc 프로필로 fixture 쪽 하나 파싱
    private static StructuredDocument parse(String page) {

        return SERVICE
                .parse(
                        ParseFixtures
                                .asciidocPage(page),
                        INCLUDES
                );
    }

    // include 전부와 같은 Build의 쪽 둘
    private static Map<String, SourceDocument> withPages() {

        Map<String, SourceDocument> inputs = new TreeMap<>(INCLUDES);

        for (String path : List
                .of(
                        "nested/deep.adoc",
                        "titles.adoc"
                )) {
            SourceDocument page = ParseFixtures
                    .asciidocPage(path);

            inputs
                    .put(
                            page
                                    .canonicalUrl(),
                            page
                    );
        }

        return inputs;
    }

    // 보류 예외
    private static DocumentHoldException hold(
            DocumentParseService service,
            String page,
            Map<String, SourceDocument> includes
    ) {

        return assertThrows(
                DocumentHoldException.class,
                () -> service
                        .parse(
                                ParseFixtures
                                        .asciidocPage(page),
                                includes
                        )
        );
    }

    // anchor가 같은 섹션
    private static StructuredSection section(
            StructuredDocument document,
            String anchor
    ) {

        return document
                .sections()
                .stream()
                .filter(section -> anchor
                        .equals(section
                                .anchor()))
                .findFirst()
                .orElseThrow();
    }

    // 예시 파일 원문
    private static String example(String path) {

        return new String(
                ParseFixtures
                        .asciidocBytes(EXAMPLES + path),
                StandardCharsets.UTF_8
        );
    }

    // AsciiDoc 규칙만 바꾼 ENABLED 프로필
    private static ParserProfile withRules(AsciidocRules rules) {

        ParserProfile profile = ParseFixtures
                .withApproval(
                        ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE,
                        ApprovalState.ENABLED
                );

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
                profile
                        .rootSelector(),
                profile
                        .excludedSelectors(),
                profile
                        .headingSelectors(),
                profile
                        .tabSelectors(),
                profile
                        .versionMarkerRule(),
                profile
                        .fixtureHashes(),
                profile
                        .approvalState(),
                profile
                        .defaultTabRule(),
                profile
                        .preferredDefaultLabels(),
                profile
                        .canonicalLabels(),
                profile
                        .groupTypes(),
                rules
        );
    }
}
