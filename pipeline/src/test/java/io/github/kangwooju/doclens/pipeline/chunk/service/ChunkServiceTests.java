package io.github.kangwooju.doclens.pipeline.chunk.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.pack.dto.CodeVariant;
import io.github.kangwooju.doclens.core.pack.dto.DocLink;
import io.github.kangwooju.doclens.core.pack.dto.PackChunk;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.pipeline.chunk.converter.ChunkJsonConverter;
import io.github.kangwooju.doclens.pipeline.chunk.helper.BlockRenderHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.ChunkPackHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.IdentifierHelper;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;
import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("구조 산출물의 Pack 문서 변환")
class ChunkServiceTests {

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .build();

    private static final PackDocument REFERENCE = chunk(ParseFixtures
            .html("reference.html"));

    @Test
    @DisplayName("섹션마다 청크, 설명 문단과 코드는 같은 청크, 탭은 기본 변형만 content")
    void buildsChunksPerSection() {

        PackChunk chunk = REFERENCE
                .sections()
                .get(1)
                .chunks()
                .getFirst();

        assertEquals(
                5,
                REFERENCE
                        .sections()
                        .size()
        );
        assertTrue(chunk
                .content()
                .startsWith("The value a &lt; b stays literal.\n\nExample\n```java\nimport java.util.List;"));
        assertTrue(chunk
                .content()
                .contains("NOTE: Remember\nRun this first."));
        assertTrue(chunk
                .content()
                .contains("```java\n@SpringBootApplication\npublic class MyApplication {\n}\n```"));
        assertFalse(chunk
                .content()
                .contains("class MyApplication\n```"));
        assertEquals(
                "Sample Settings > Settings Files",
                chunk
                        .headingPath()
        );
    }

    @Test
    @DisplayName("청크 ID 공식, 탭 그룹 하나면 그 위치와 기본 변형 key")
    void chunkIdFollowsFormula() {

        PackChunk chunk = REFERENCE
                .sections()
                .get(1)
                .chunks()
                .getFirst();

        assertEquals(
                IdHash
                        .chunkId(
                                ParseFixtures.DOCUMENT_ID,
                                IdHash
                                        .headingLocator(
                                                "settings.files",
                                                0
                                        ),
                                IdHash
                                        .tabGroupLocator(
                                                "_tabs_1",
                                                0
                                        ),
                                "java",
                                0,
                                chunk
                                        .content()
                        ),
                chunk
                        .chunkId()
        );
    }

    @Test
    @DisplayName("탭 그룹의 모든 변형을 코드 변형으로, 기본은 하나, 코드 하나뿐인 변형은 원문 코드")
    void storesEveryVariant() {

        PackChunk chunk = REFERENCE
                .sections()
                .get(1)
                .chunks()
                .getFirst();
        List<CodeVariant> variants = chunk
                .codeVariants();

        assertEquals(
                List
                        .of(
                                "java",
                                "kotlin"
                        ),
                variants
                        .stream()
                        .map(CodeVariant::variant)
                        .toList()
        );
        assertEquals(
                1,
                variants
                        .stream()
                        .filter(CodeVariant::isDefault)
                        .count()
        );
        assertEquals(
                "@SpringBootApplication\nclass MyApplication",
                variants
                        .get(1)
                        .code()
        );

        List<Map<String, Object>> blocks = blocks(chunk);

        assertEquals(
                "TAB_GROUP",
                blocks
                        .get(variants
                                .getFirst()
                                .blockIndex())
                        .get("kind")
        );
    }

    @Test
    @DisplayName("식별자는 인라인 코드와 모든 탭 변형 코드에서, 다른 문서 링크만 docLinks")
    void extractsIdentifiersAndLinks() {

        PackChunk preamble = REFERENCE
                .sections()
                .getFirst()
                .chunks()
                .getFirst();
        PackChunk files = REFERENCE
                .sections()
                .get(1)
                .chunks()
                .getFirst();
        PackChunk yaml = REFERENCE
                .sections()
                .get(2)
                .chunks()
                .getFirst();

        assertEquals(
                List
                        .of("application.properties"),
                preamble
                        .identifiers()
        );
        assertEquals(
                List
                        .of(new DocLink(
                                "https://docs.spring.io/spring-boot/4.1.2/reference/features/other.html",
                                "loading"
                        )),
                preamble
                        .docLinks()
        );
        assertTrue(files
                .identifiers()
                .containsAll(List
                        .of(
                                "@SpringBootApplication",
                                "MyApplication",
                                "java.util.List"
                        )));
        assertFalse(files
                .identifiers()
                .contains("a &lt; b"));
        assertTrue(yaml
                .identifiers()
                .containsAll(List
                        .of(
                                "spring.main.banner-mode",
                                "server.port"
                        )));
    }

    @Test
    @DisplayName("AsciiDoc 청크는 구성원마다 같은 섹션 인용, 문서 출처에 쓴 include, HTML은 두 키 없음")
    void carriesCitationAndIncludes() {

        PackDocument asciidoc = service()
                .chunk(ParseFixtures
                        .asciidocService()
                        .parse(
                                ParseFixtures
                                        .asciidocPage("sample.adoc"),
                                ParseFixtures
                                        .asciidocIncludes()
                        ));
        PackChunk config = asciidoc
                .sections()
                .get(7)
                .chunks()
                .getFirst();
        List<Map<String, Object>> members = sourceMap(config);
        Map<String, Object> document = JSON
                .readValue(
                        asciidoc
                                .sourceMapJson(),
                        new TypeReference<>() {
                        }
                );

        assertTrue(members
                .size() > 1);
        assertEquals(
                Set
                        .of(Map
                                .of(
                                        "readerUrl",
                                        "https://docs.spring.io/spring-boot/4.1/reference/sample.html#sample.config",
                                        "sourceUrl",
                                        "https://github.com/spring-projects/spring-boot/blob/0123456789abcdef0123456789abcdef01234567/"
                                                + ParseFixtures.PAGES + "sample.adoc?plain=1#L54"
                                )),
                new HashSet<>(members
                        .stream()
                        .map(member -> member
                                .get("citation"))
                        .toList())
        );
        assertEquals(
                5,
                ((List<?>) document
                        .get("includes"))
                        .size()
        );
        assertTrue(sourceMap(REFERENCE
                .sections()
                .get(1)
                .chunks()
                .getFirst())
                .stream()
                .noneMatch(member -> member
                        .containsKey("citation")));
        assertFalse(REFERENCE
                .sourceMapJson()
                .contains("\"includes\""));
    }

    @Test
    @DisplayName("blocksJson의 content 범위는 content 안 구성원 위치와 일치")
    void contentRangesMatch() {

        for (var section : REFERENCE
                .sections()) {
            for (PackChunk chunk : section
                    .chunks()) {
                String content = chunk
                        .content();

                for (Map<String, Object> block : blocks(chunk)) {
                    int start = (Integer) block
                            .get("contentStart");
                    int end = (Integer) block
                            .get("contentEnd");

                    assertTrue(start >= 0 && start <= end && end <= content
                            .codePointCount(
                                    0,
                                    content
                                            .length()
                            ));
                }
            }
        }
    }

    @Test
    @DisplayName("12000바이트 넘는 문단은 문장 경계 조각, 이어 붙이면 원래 문단")
    void splitsLongParagraph() {

        String sentence = "한글 문장은 세 바이트 글자로 길어진다. ";
        String paragraph = sentence
                .repeat(800)
                .strip();
        PackDocument document = chunk(ParseFixtures
                .page("<div class=\"paragraph\"><p>" + paragraph + "</p></div>"));
        List<PackChunk> chunks = document
                .sections()
                .getFirst()
                .chunks();
        StringBuilder joined = new StringBuilder();

        assertTrue(chunks
                .size() > 1);

        for (PackChunk chunk : chunks) {
            assertTrue(TextNormalizer
                    .utf8Length(chunk
                            .content()) <= ChunkPackHelper.MAX_BYTES);
            joined
                    .append(chunk
                            .content());
        }

        assertEquals(
                paragraph,
                joined
                        .toString()
        );
        assertEquals(
                true,
                blocks(chunks
                        .get(1))
                        .getFirst()
                        .get("continuation")
        );
    }

    @Test
    @DisplayName("짧은 문단 33개는 32블록 상한으로 나뉨")
    void limitsBlocksPerChunk() {

        String paragraphs = "<div class=\"paragraph\"><p>Short line.</p></div>"
                .repeat(33);
        List<PackChunk> chunks = chunk(ParseFixtures
                .page(paragraphs))
                .sections()
                .getFirst()
                .chunks();

        assertEquals(
                2,
                chunks
                        .size()
        );

        for (PackChunk chunk : chunks) {
            assertTrue(blocks(chunk)
                    .size() <= ChunkPackHelper.MAX_BLOCKS);
        }
    }

    @Test
    @DisplayName("둘째 청크는 직전 청크 끝의 짧은 문단을 OVERLAP으로 앞에 붙임")
    void overlapsLastParagraph() {

        StringBuilder body = new StringBuilder();

        for (int index = 0; index < 10; index++) {
            body
                    .append("<div class=\"paragraph\"><p>")
                    .append(("p" + index + " ")
                            .repeat(230))
                    .append("</p></div>");
        }

        List<PackChunk> chunks = chunk(ParseFixtures
                .page(body
                        .toString()))
                .sections()
                .getFirst()
                .chunks();
        List<Map<String, Object>> second = blocks(chunks
                .get(1));
        List<Map<String, Object>> first = blocks(chunks
                .getFirst());

        assertEquals(
                2,
                chunks
                        .size()
        );
        assertEquals(
                "OVERLAP",
                second
                        .getFirst()
                        .get("role")
        );
        assertEquals(
                first
                        .getLast()
                        .get("blockId"),
                second
                        .getFirst()
                        .get("blockId")
        );
        assertTrue(chunks
                .getFirst()
                .content()
                .length() <= ChunkPackHelper.TARGET_BYTES);
    }

    @Test
    @DisplayName("큰 코드는 혼자 oversized 청크, 큰 표는 header를 반복한 행 묶음")
    void handlesOversizedCodeAndTables() {

        String code = "line of code\n"
                .repeat(1500);
        StringBuilder rows = new StringBuilder();

        for (int index = 0; index < 400; index++) {
            rows
                    .append("<tr><td><p>key")
                    .append(index)
                    .append("</p></td><td><p>")
                    .append("value ".repeat(8))
                    .append("</p></td></tr>");
        }

        PackDocument document = chunk(ParseFixtures
                .page("<div class=\"paragraph\"><p>Intro.</p></div><div class=\"listingblock\"><pre>" + code
                        + "</pre></div><table class=\"tableblock\"><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>"
                        + rows + "</tbody></table>"));
        List<PackChunk> chunks = document
                .sections()
                .getFirst()
                .chunks();
        Map<String, Object> codeBlock = blocks(chunks
                .get(1))
                .getFirst();

        assertEquals(
                "CODE",
                codeBlock
                        .get("kind")
        );
        assertEquals(
                true,
                codeBlock
                        .get("oversized")
        );
        assertEquals(
                1,
                blocks(chunks
                        .get(1))
                        .size()
        );

        List<Map<String, Object>> tableParts = chunks
                .subList(
                        2,
                        chunks
                                .size()
                )
                .stream()
                .map(chunk -> blocks(chunk)
                        .getLast())
                .toList();

        assertTrue(tableParts
                .size() > 1);
        assertEquals(
                0,
                tableParts
                        .getFirst()
                        .get("rowStart")
        );
        assertEquals(
                400,
                tableParts
                        .getLast()
                        .get("rowEndExclusive")
        );
        assertEquals(
                true,
                tableParts
                        .get(1)
                        .get("headerRepeated")
        );
        assertTrue(chunks
                .get(3)
                .content()
                .startsWith("| Key | Value |\n| --- | --- |\n"));
    }

    @Test
    @DisplayName("본문 행 없는 큰 표는 통째 oversized, 반복하지 않는 큰 header는 첫 행과 따로")
    void keepsLargeHeadersSeparate() {

        String wide = "w"
                .repeat(13000);
        List<PackChunk> headerOnly = chunk(ParseFixtures
                .page("<table class=\"tableblock\"><thead><tr><th>" + wide + "</th></tr></thead></table>"))
                .sections()
                .getFirst()
                .chunks();

        assertEquals(
                1,
                headerOnly
                        .size()
        );
        assertEquals(
                true,
                blocks(headerOnly
                        .getFirst())
                        .getFirst()
                        .get("oversized")
        );

        StringBuilder rows = new StringBuilder();

        for (int index = 0; index < 3; index++) {
            rows
                    .append("<tr><td>")
                    .append("r"
                            .repeat(7500))
                    .append("</td></tr>");
        }

        List<Map<String, Object>> parts = chunk(ParseFixtures
                .page("<table class=\"tableblock\"><thead><tr><th>" + "h"
                        .repeat(5000) + "</th></tr></thead><tbody>" + rows + "</tbody></table>"))
                .sections()
                .getFirst()
                .chunks()
                .stream()
                .map(chunk -> blocks(chunk)
                        .getFirst())
                .toList();

        assertEquals(
                0,
                parts
                        .getFirst()
                        .get("rowEndExclusive")
        );
        assertTrue(parts
                .stream()
                .noneMatch(part -> Boolean.TRUE
                        .equals(part
                                .get("oversized"))));
    }

    @Test
    @DisplayName("큰 목록은 항목 줄로 나뉘고 조각을 줄바꿈으로 이으면 원래 목록")
    void splitsLongListByLines() {

        String sentence = "This sentence is long enough to fill the list item. ";
        String body = "<div class=\"ulist\"><ul><li><p>a</p></li><li><p>" + sentence
                .repeat(300) + "</p></li><li><p>c</p></li></ul></div>";
        List<PackChunk> chunks = chunk(ParseFixtures
                .page(body))
                .sections()
                .getFirst()
                .chunks();
        String list = ParseFixtures
                .service()
                .parse(ParseFixtures
                        .source(ParseFixtures
                                .page(body)))
                .sections()
                .getFirst()
                .blocks()
                .getFirst()
                .text();

        assertTrue(chunks
                .size() > 1);
        assertEquals(
                list
                        .replace(
                                "\n",
                                ""
                        ),
                chunks
                        .stream()
                        .map(PackChunk::content)
                        .map(content -> content
                                .replace(
                                        "\n",
                                        ""
                                ))
                        .reduce(
                                "",
                                String::concat
                        )
        );
    }

    @Test
    @DisplayName("한 청크의 탭 그룹이 둘이면 위치와 기본 key의 집합 digest")
    void digestsTwoTabGroups() {

        String group = "<div id=\"%s\" class=\"openblock tabs\"><div class=\"content\"><div class=\"ulist tablist\"><ul>"
                + "<li id=\"%s_j\" class=\"tab\"><p>Java</p></li><li id=\"%s_k\" class=\"tab\"><p>Kotlin</p></li></ul></div>"
                + "<div class=\"tabpanel\" aria-labelledby=\"%s_j\"><div class=\"listingblock\"><pre>j</pre></div></div>"
                + "<div class=\"tabpanel\" aria-labelledby=\"%s_k\"><div class=\"listingblock\"><pre>k</pre></div></div></div></div>";
        PackChunk chunk = chunk(ParseFixtures
                .page(group
                        .formatted(
                                "a",
                                "a",
                                "a",
                                "a",
                                "a"
                        ) + group
                                .formatted(
                                        "b",
                                        "b",
                                        "b",
                                        "b",
                                        "b"
                                )))
                .sections()
                .getFirst()
                .chunks()
                .getFirst();

        assertEquals(
                IdHash
                        .chunkId(
                                ParseFixtures.DOCUMENT_ID,
                                IdHash
                                        .headingLocator(
                                                "page-title",
                                                0
                                        ),
                                IdHash
                                        .tabGroupLocatorOf(List
                                                .of(
                                                        IdHash
                                                                .tabGroupLocator(
                                                                        "a",
                                                                        0
                                                                ),
                                                        IdHash
                                                                .tabGroupLocator(
                                                                        "b",
                                                                        0
                                                                )
                                                )),
                                IdHash
                                        .variantKeyOf(List
                                                .of(
                                                        "java",
                                                        "java"
                                                )),
                                0,
                                chunk
                                        .content()
                        ),
                chunk
                        .chunkId()
        );
        assertEquals(
                4,
                chunk
                        .codeVariants()
                        .size()
        );
    }

    @Test
    @DisplayName("같은 입력은 같은 Pack 문서, 일반 청크 상한과 코드 중복 없음")
    void isDeterministicAndBounded() {

        PackDocument again = chunk(ParseFixtures
                .html("reference.html"));
        Set<Object> codeBlocks = new HashSet<>();

        assertEquals(
                REFERENCE,
                again
        );

        for (var section : REFERENCE
                .sections()) {
            for (PackChunk chunk : section
                    .chunks()) {
                List<Map<String, Object>> blocks = blocks(chunk);

                assertTrue(blocks
                        .size() <= ChunkPackHelper.MAX_BLOCKS);
                assertTrue(TextNormalizer
                        .utf8Length(chunk
                                .content()) <= ChunkPackHelper.MAX_BYTES);

                for (Map<String, Object> block : blocks) {
                    boolean primaryCode = "PRIMARY"
                            .equals(block
                                    .get("role")) && List
                                            .of(
                                                    "CODE",
                                                    "TABLE",
                                                    "TAB_GROUP"
                                            )
                                            .contains(block
                                                    .get("kind"));

                    if (primaryCode) {
                        assertTrue(codeBlocks
                                .add(block
                                        .get("blockId")));
                    }
                }
            }
        }

        assertEquals(
                "chunker-v4-jdk21",
                ChunkService.CHUNKER_POLICY_VERSION
        );
    }

    // 시험 HTML을 파싱하고 청크로
    static PackDocument chunk(String html) {

        return service()
                .chunk(ParseFixtures
                        .service()
                        .parse(ParseFixtures
                                .source(html)));
    }

    // Spring 없이 만든 청크 서비스
    static ChunkService service() {

        return new ChunkService(
                new BlockRenderHelper(),
                new ChunkPackHelper(),
                new IdentifierHelper(),
                new ChunkJsonConverter()
        );
    }

    // 청크의 sourceMapJson
    private static List<Map<String, Object>> sourceMap(PackChunk chunk) {

        return JSON
                .readValue(
                        chunk
                                .sourceMapJson(),
                        new TypeReference<>() {
                        }
                );
    }

    // 청크의 blocksJson
    private static List<Map<String, Object>> blocks(PackChunk chunk) {

        return JSON
                .readValue(
                        chunk
                                .blocksJson(),
                        new TypeReference<>() {
                        }
                );
    }
}
