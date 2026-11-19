package io.github.kangwooju.doclens.pipeline.parse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.converter.StructuredDocumentConverter;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredSection;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableRow;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

@DisplayName("원본 HTML의 구조 산출물 변환")
class DocumentParseServiceTests {

    private static final String REFERENCE = ParseFixtures
            .html("reference.html");

    @Test
    @DisplayName("heading 순서대로 섹션, 부모, 경로, anchor 생성")
    void buildsSectionTree() {

        StructuredDocument document = parse(REFERENCE);
        List<StructuredSection> sections = document
                .sections();

        assertEquals(
                "Sample Settings",
                document
                        .title()
        );
        assertEquals(
                5,
                sections
                        .size()
        );
        assertEquals(
                List
                        .of(
                                "Sample Settings",
                                "Settings Files",
                                "YAML Files"
                        ),
                sections
                        .get(2)
                        .headingPath()
        );
        assertEquals(
                sections
                        .get(1)
                        .sectionId(),
                sections
                        .get(2)
                        .parentSectionId()
        );
        assertEquals(
                "settings.files.yaml",
                sections
                        .get(2)
                        .anchor()
        );
        assertNull(sections
                .get(3)
                .anchor());
        assertEquals(
                IdHash
                        .headingLocator(List
                                .of(
                                        0,
                                        0,
                                        1
                                )),
                sections
                        .get(3)
                        .headingLocator()
        );
        assertEquals(
                IdHash
                        .headingLocator(
                                "settings.files",
                                1
                        ),
                sections
                        .get(4)
                        .headingLocator()
        );
        assertEquals(
                IdHash
                        .sectionId(
                                ParseFixtures.DOCUMENT_ID,
                                IdHash
                                        .headingLocator(
                                                "settings.files",
                                                0
                                        )
                        ),
                sections
                        .get(1)
                        .sectionId()
        );
    }

    @Test
    @DisplayName("제외 선택자와 구버전 배너, 경로 표시, 이전 다음 링크는 블록에서 빠짐")
    void skipsExcludedElements() {

        String all = allText(parse(REFERENCE));

        assertFalse(all
                .contains("old version"));
        assertFalse(all
                .contains("Home"));
        assertFalse(all
                .contains("Next"));
    }

    @Test
    @DisplayName("문단 공백 접기, entity 한 번만 해석, 인라인 코드와 링크 위치")
    void paragraphsKeepInlinePositions() {

        Block preamble = section(
                0
        )
                .blocks()
                .getFirst();
        String text = preamble
                .text();

        assertEquals(
                "Settings are read from application.properties at startup. See loading rules and mail.",
                text
        );

        InlineCode code = preamble
                .inlineCodes()
                .getFirst();

        assertEquals(
                "application.properties",
                code
                        .text()
        );
        assertEquals(
                "application.properties",
                substring(
                        text,
                        code
                                .start(),
                        code
                                .text()
                )
        );

        BlockLink relative = preamble
                .links()
                .get(0);
        BlockLink mail = preamble
                .links()
                .get(1);

        assertEquals(
                "https://docs.spring.io/spring-boot/4.1.2/reference/features/other.html",
                relative
                        .targetUrl()
        );
        assertEquals(
                "loading",
                relative
                        .targetAnchor()
        );
        assertEquals(
                "loading rules",
                substring(
                        text,
                        relative
                                .start(),
                        relative
                                .label()
                )
        );
        assertNull(mail
                .targetUrl());
        assertEquals(
                "",
                mail
                        .targetAnchor()
        );
        assertEquals(
                "The value a &lt; b stays literal.",
                section(1)
                        .blocks()
                        .getFirst()
                        .text()
        );
    }

    @Test
    @DisplayName("코드는 공백 보존, 코드 접기 대체 문구와 callout 표시 제거, 언어와 제목 보존")
    void codeKeepsWhitespace() {

        Block code = section(1)
                .blocks()
                .get(1);

        assertEquals(
                BlockKind.CODE,
                code
                        .kind()
        );
        assertEquals(
                "import java.util.List;\nclass Sample {\n\tString name = \"x\";  \n}\n",
                code
                        .text()
        );
        assertEquals(
                "java",
                code
                        .language()
        );
        assertEquals(
                "Example",
                code
                        .title()
        );
    }

    @Test
    @DisplayName("callout 설명은 번호 목록, admonition 안 코드는 펼쳐서 차례로")
    void flattensAdmonitionWithCode() {

        List<Block> blocks = section(1)
                .blocks();

        assertEquals(
                "1. The name field.",
                blocks
                        .get(2)
                        .text()
        );
        assertEquals(
                List
                        .of(
                                BlockKind.ADMONITION,
                                BlockKind.CODE,
                                BlockKind.ADMONITION
                        ),
                blocks
                        .subList(
                                3,
                                6
                        )
                        .stream()
                        .map(Block::kind)
                        .toList()
        );
        assertEquals(
                "Remember\nRun this first.",
                blocks
                        .get(3)
                        .text()
        );
        assertEquals(
                "note",
                blocks
                        .get(3)
                        .admonitionType()
        );
        assertEquals(
                "./run.sh --check",
                blocks
                        .get(4)
                        .text()
        );
        assertEquals(
                "Then check the log.",
                blocks
                        .get(5)
                        .text()
        );
    }

    @Test
    @DisplayName("탭은 모든 변형 보존, 표식이 없으면 프로필 규칙으로 첫 탭이 기본")
    void keepsEveryTabVariant() {

        TabGroup java = section(1)
                .blocks()
                .get(6)
                .tabGroup();
        TabGroup config = section(2)
                .blocks()
                .getFirst()
                .tabGroup();

        assertEquals(
                IdHash
                        .tabGroupLocator(
                                "_tabs_1",
                                0
                        ),
                java
                        .groupLocator()
        );
        assertEquals(
                VariantGroupType.LANGUAGE,
                java
                        .groupType()
        );
        assertEquals(
                "java",
                java
                        .defaultVariant()
        );
        assertEquals(
                "PROFILE_RULE:FIRST_IN_SOURCE_ORDER",
                java
                        .defaultEvidence()
        );
        assertEquals(
                List
                        .of(
                                "java",
                                "kotlin"
                        ),
                java
                        .variants()
                        .stream()
                        .map(variant -> variant
                                .variantKey())
                        .toList()
        );
        assertEquals(
                "@SpringBootApplication\nclass MyApplication",
                java
                        .variants()
                        .get(1)
                        .blocks()
                        .getFirst()
                        .text()
        );
        assertEquals(
                VariantGroupType.CONFIG_STYLE,
                config
                        .groupType()
        );
        assertEquals(
                "properties",
                config
                        .defaultVariant()
        );
    }

    @Test
    @DisplayName("표는 header 행과 원래 rowspan 보존, 셀 인라인 코드 기록")
    void keepsTableShape() {

        Block table = section(2)
                .blocks()
                .get(1);
        List<TableRow> rows = table
                .table()
                .rows();

        assertEquals(
                "Table 1. Sample keys",
                table
                        .table()
                        .caption()
        );
        assertTrue(rows
                .getFirst()
                .header());
        assertFalse(rows
                .get(1)
                .header());
        assertEquals(
                2,
                rows
                        .get(1)
                        .cells()
                        .getFirst()
                        .rowspan()
        );
        assertEquals(
                "server.port",
                rows
                        .get(1)
                        .cells()
                        .getFirst()
                        .inlineCodes()
                        .getFirst()
                        .text()
        );
        assertEquals(
                1,
                rows
                        .get(2)
                        .cells()
                        .size()
        );
    }

    @Test
    @DisplayName("목록은 항목 줄, ol은 start 번호, 중첩은 들여쓰기, 정의 목록은 용어와 설명")
    void rendersLists() {

        List<Block> blocks = section(3)
                .blocks();

        assertEquals(
                "- First item\n  - Nested item\n- Second item",
                blocks
                        .get(0)
                        .text()
        );
        assertEquals(
                "item",
                blocks
                        .get(0)
                        .inlineCodes()
                        .getFirst()
                        .text()
        );
        assertEquals(
                "3. Third step\n4. Fourth step",
                blocks
                        .get(1)
                        .text()
        );
        assertEquals(
                "- Term: Its meaning.",
                blocks
                        .get(2)
                        .text()
        );
    }

    @Test
    @DisplayName("블록 ID는 섹션 ID와 섹션 안 순번, 출처는 domPath와 heading 위치")
    void blockIdsFollowPosition() {

        StructuredSection section = section(1);
        Block first = section
                .blocks()
                .getFirst();

        assertEquals(
                IdHash
                        .blockId(
                                section
                                        .sectionId(),
                                0
                        ),
                first
                        .blockId()
        );
        assertEquals(
                section
                        .headingLocator(),
                first
                        .sourcePointer()
                        .headingLocator()
        );
        assertFalse(first
                .sourcePointer()
                .domPath()
                .isEmpty());
    }

    @Test
    @DisplayName("같은 입력은 같은 결과, 문서가 다르면 같은 heading도 섹션 ID가 다름")
    void isDeterministicAndScopedByDocument() {

        StructuredDocument first = parse(REFERENCE);
        StructuredDocument second = parse(REFERENCE);
        StructuredDocument other = ParseFixtures
                .service()
                .parse(ParseFixtures
                        .source(
                                REFERENCE,
                                ParseFixtures.OTHER_DOCUMENT_ID
                        ));

        assertEquals(
                first,
                second
        );
        assertNotEquals(
                first
                        .sections()
                        .get(1)
                        .sectionId(),
                other
                        .sections()
                        .get(1)
                        .sectionId()
        );
    }

    @Test
    @DisplayName("구조 산출물은 canonical JSON으로 왕복해도 같음")
    void roundTripsThroughJson() {

        StructuredDocumentConverter converter = new StructuredDocumentConverter();
        StructuredDocument document = parse(REFERENCE);
        String json = converter
                .toJson(document);

        assertEquals(
                document,
                converter
                        .fromJson(json)
        );
        assertEquals(
                json,
                converter
                        .toJson(converter
                                .fromJson(json))
        );
    }

    @Test
    @DisplayName("pre 시작 직후 줄바꿈 하나는 HTML 규칙대로 빠짐")
    void dropsNewlineAfterPreStart() {

        StructuredDocument document = parse(ParseFixtures
                .page("<div class=\"listingblock\"><div class=\"content\"><pre>\n\nfirst\r\nsecond</pre></div></div>"));

        assertEquals(
                "\nfirst\nsecond",
                document
                        .sections()
                        .getFirst()
                        .blocks()
                        .getFirst()
                        .text()
        );
    }

    @Test
    @DisplayName("서버 선택 표식 하나는 그 변형, 둘 이상은 보류")
    void usesServerMarker() {

        String marked = tabs(
                "<li id=\"t_java\" class=\"tab\"><p>Java</p></li><li id=\"t_kotlin\" class=\"tab is-selected\"><p>Kotlin</p></li>",
                "t_java",
                "t_kotlin"
        );
        TabGroup group = parse(marked)
                .sections()
                .getFirst()
                .blocks()
                .getFirst()
                .tabGroup();

        assertEquals(
                "kotlin",
                group
                        .defaultVariant()
        );
        assertEquals(
                "SERVER_MARKER",
                group
                        .defaultEvidence()
        );
        assertHold(
                HoldReason.TAB_DEFAULT_UNRESOLVED,
                tabs(
                        "<li id=\"t_java\" class=\"tab is-selected\"><p>Java</p></li><li id=\"t_kotlin\" class=\"tab is-selected\"><p>Kotlin</p></li>",
                        "t_java",
                        "t_kotlin"
                )
        );
    }

    @Test
    @DisplayName("대응표 밖 원문 라벨 java는 literal key가 아닌 hex, 유형은 other")
    void unmappedLabelIsHashed() {

        TabGroup group = parse(tabs(
                "<li id=\"t_a\" class=\"tab\"><p>java</p></li><li id=\"t_b\" class=\"tab\"><p>Java</p></li>",
                "t_a",
                "t_b"
        ))
                .sections()
                .getFirst()
                .blocks()
                .getFirst()
                .tabGroup();

        assertEquals(
                IdHash
                        .variantKey(
                                "java",
                                0,
                                false
                        ),
                group
                        .variants()
                        .get(0)
                        .variantKey()
        );
        assertEquals(
                IdHash
                        .variantKey(
                                "java",
                                1,
                                true
                        ),
                group
                        .variants()
                        .get(1)
                        .variantKey()
        );
        assertEquals(
                VariantGroupType.OTHER,
                group
                        .groupType()
        );
    }

    @Test
    @DisplayName("프로필 미승인, 프로필 없음은 선택자 미검증 보류")
    void holdsWithoutEnabledProfile() {

        DocumentHoldException candidate = assertThrows(
                DocumentHoldException.class,
                () -> ParseFixtures
                        .service(ParserProfileStore.SPRING_BOOT_REFERENCE)
                        .parse(ParseFixtures
                                .source(REFERENCE))
        );

        assertEquals(
                HoldReason.SELECTOR_UNVERIFIED,
                candidate
                        .reason()
        );
        assertEquals(
                HoldReason.SELECTOR_UNVERIFIED,
                assertThrows(
                        DocumentHoldException.class,
                        () -> new DocumentParseService(
                                new ParserProfileStore(List
                                        .of()),
                                new io.github.kangwooju.doclens.pipeline.parse.policy.ParserProfilePolicy(),
                                new io.github.kangwooju.doclens.pipeline.parse.helper.BlockExtractHelper()
                        )
                                .parse(ParseFixtures
                                        .source(REFERENCE))
                )
                        .reason()
        );
    }

    @Test
    @DisplayName("버전 표식 없음, 불일치, 본문 root 0개와 2개, 빈 본문은 보류")
    void holdsOnStructureMismatch() {

        assertHold(
                HoldReason.VERSION_MARKER_MISSING,
                REFERENCE
                        .replace(
                                "<meta name=\"version\" content=\"4.1.2\">",
                                ""
                        )
        );
        assertHold(
                HoldReason.VERSION_MARKER_CONFLICT,
                REFERENCE
                        .replace(
                                "content=\"4.1.2\"",
                                "content=\"4.1.1\""
                        )
        );
        assertHold(
                HoldReason.ROOT_AMBIGUOUS,
                REFERENCE
                        .replace(
                                "article class=\"doc\"",
                                "article class=\"page\""
                        )
        );
        assertHold(
                HoldReason.ROOT_AMBIGUOUS,
                REFERENCE
                        .replace(
                                "</main>",
                                "<article class=\"doc\"></article></main>"
                        )
        );
        assertHold(
                HoldReason.BODY_EMPTY,
                ParseFixtures
                        .page("")
        );
        assertHold(
                HoldReason.TITLE_MISSING,
                "<html><head><meta name=\"version\" content=\"4.1.2\"></head><body><article class=\"doc\"><div class=\"paragraph\"><p>x</p></div></article></body></html>"
        );
    }

    @Test
    @DisplayName("중첩 탭, 목록과 표 셀 안 코드, 1 MiB 넘는 코드는 보류")
    void holdsUnsupportedNesting() {

        assertHold(
                HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                tabs(
                        "<li id=\"t_a\" class=\"tab\"><p>Java</p></li><li id=\"t_b\" class=\"tab\"><p>Kotlin</p></li>",
                        "t_a",
                        "t_b"
                )
                        .replace(
                                "<pre>a</pre>",
                                "<div class=\"openblock tabs\"><div class=\"content\"></div></div>"
                        )
        );
        assertHold(
                HoldReason.BLOCK_NESTING_UNSUPPORTED,
                ParseFixtures
                        .page("<div class=\"ulist\"><ul><li><p>x</p><div class=\"listingblock\"><pre>y</pre></div></li></ul></div>")
        );
        assertHold(
                HoldReason.BLOCK_NESTING_UNSUPPORTED,
                ParseFixtures
                        .page("<table class=\"tableblock\"><tbody><tr><td><div class=\"listingblock\"><pre>y</pre></div></td></tr></tbody></table>")
        );
        assertHold(
                HoldReason.CODE_OVER_HARD_LIMIT,
                ParseFixtures
                        .page("<div class=\"listingblock\"><pre>" + "x"
                                .repeat(1024 * 1024 + 1) + "</pre></div>")
        );
    }

    @Test
    @DisplayName("잘못된 UTF-8은 보류, 원본 hash 불일치는 입력 오류")
    void checksRawBytes() {

        byte[] invalid = {(byte) 0xff, (byte) 0xfe};

        assertEquals(
                HoldReason.INVALID_UNICODE,
                assertThrows(
                        DocumentHoldException.class,
                        () -> ParseFixtures
                                .service()
                                .parse(ParseFixtures
                                        .bytes(
                                                invalid,
                                                ParseFixtures.DOCUMENT_ID
                                        ))
                )
                        .reason()
        );

        var source = ParseFixtures
                .source(REFERENCE);
        var tampered = new io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument(
                source
                        .documentId(),
                source
                        .project(),
                source
                        .exactVersion(),
                source
                        .documentType(),
                source
                        .canonicalUrl(),
                source
                        .originalUrl(),
                source
                        .finalUrl(),
                source
                        .sourceSnapshotId(),
                "sha256:" + "0"
                        .repeat(64),
                source
                        .rawBytes()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ParseFixtures
                        .service()
                        .parse(tampered)
        );
    }

    // 시험 문서 파싱
    private static StructuredDocument parse(String html) {

        return ParseFixtures
                .service()
                .parse(ParseFixtures
                        .source(html));
    }

    // 기준 문서의 index번째 섹션
    private static StructuredSection section(int index) {

        return parse(REFERENCE)
                .sections()
                .get(index);
    }

    // 탭 두 개짜리 최소 문서
    private static String tabs(
            String tabItems,
            String firstId,
            String secondId
    ) {

        return ParseFixtures
                .page("<div id=\"g\" class=\"openblock tabs\"><div class=\"content\"><div class=\"ulist tablist\"><ul>"
                        + tabItems + "</ul></div><div class=\"tabpanel\" aria-labelledby=\"" + firstId
                        + "\"><div class=\"listingblock\"><pre>a</pre></div></div><div class=\"tabpanel\" aria-labelledby=\""
                        + secondId + "\"><div class=\"listingblock\"><pre>b</pre></div></div></div></div>");
    }

    // 보류 이유 확인
    private static void assertHold(
            HoldReason reason,
            String html
    ) {

        Executable parse = () -> parse(html);

        assertEquals(
                reason,
                assertThrows(
                        DocumentHoldException.class,
                        parse
                )
                        .reason()
        );
    }

    // code point 위치의 부분 문자열
    private static String substring(
            String text,
            int start,
            String expected
    ) {

        int from = text
                .offsetByCodePoints(
                        0,
                        start
                );

        return text
                .substring(
                        from,
                        from + expected
                                .length()
                );
    }

    // 모든 블록 텍스트 연결
    private static String allText(StructuredDocument document) {

        return document
                .sections()
                .stream()
                .flatMap(section -> section
                        .blocks()
                        .stream())
                .map(block -> block
                        .text() == null ? "" : block
                                .text())
                .reduce(
                        "",
                        String::concat
                );
    }
}
