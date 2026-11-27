package io.github.kangwooju.doclens.pipeline.parse.helper;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.ContentNode;
import org.asciidoctor.ast.DescriptionList;
import org.asciidoctor.ast.DescriptionListEntry;
import org.asciidoctor.ast.Document;
import org.asciidoctor.ast.ListItem;
import org.asciidoctor.ast.PhraseNode;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockMacroProcessor;
import org.asciidoctor.extension.IncludeProcessor;
import org.asciidoctor.extension.InlineMacroProcessor;
import org.asciidoctor.extension.Name;
import org.asciidoctor.extension.PreprocessorReader;
import org.asciidoctor.extension.Treeprocessor;
import org.asciidoctor.jruby.AsciidoctorJRuby;
import org.asciidoctor.log.Severity;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocHtml;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocRules;
import io.github.kangwooju.doclens.pipeline.parse.dto.ResolvedInclude;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.TitleSource;
import lombok.extern.slf4j.Slf4j;

/**
 * AsciiDoc 원본 하나를 기존 HTML 경로가 읽는 HTML로 바꾸는 변환
 * 봉인된 include와 같은 Build 쪽만 읽고 파일과 네트워크는 쓰지 않음, 못 찾은 대상과 경고, 잔여 글자는 판정 재료로 돌려줌
 * 엔진은 첫 AsciiDoc 문서에서 한 번 만들고 호출별 상태는 호출이 끝나면 비움
 */
@Slf4j
@Component
public class AsciidocHtmlHelper implements AutoCloseable {

    private static final String UNRESOLVED_LINK = "https://unresolved.invalid/";

    private static final String FIXED_DATE = "1970-01-01";

    private static final String FIXED_TIME = "00:00:00+0000";

    private static final String FIXED_YEAR = "1970";

    private static final Pattern CONFIGPROPS = Pattern
            .compile("(?m)^\\[configprops(?:%\\w+)?,(\\w+)\\]$");

    private static final Pattern CROSS_MODULE_XREF = Pattern
            .compile("xref:([A-Za-z][\\w-]*):([^\\[\\s]+)\\[([^\\]]*)\\]");

    private static final Pattern SAME_MODULE_XREF = Pattern
            .compile("xref:([^\\[\\s:#]+\\.adoc)(?:#([^\\[\\s]+))?\\[\\]");

    private static final Pattern ANCHOR_LINE = Pattern
            .compile("^\\[\\[([^\\],]+)(?:,[^\\]]*)?\\]\\]\\s*$");

    private static final Pattern HEADING_LINE = Pattern
            .compile("^=+\\s+(.+?)\\s*$");

    private static final Pattern MACRO_RESIDUE = Pattern
            .compile("\\b[a-z][a-z0-9-]*::?[^\\s\\[\\]]*\\[");

    private static final Pattern ATTRIBUTE_RESIDUE = Pattern
            .compile("\\{[a-z][a-z0-9-]*\\}");

    private static final Pattern TAG_DIRECTIVE = Pattern
            .compile("\\b(tag|end)::(\\S+?)\\[\\](?=$|[ \\r])");

    private static final Pattern RESOURCE_ID = Pattern
            .compile("^(?:([\\w-]+):)?(example|partial)\\$(.+)$");

    private static final Pattern MEMBER_PARAMETERS = Pattern
            .compile("\\((.*)\\)");

    private static final List<String> COUNT_KEYS = List
            .of(
                    "bareLinkAttributes",
                    "crossModuleXrefs",
                    "generatedMarkers",
                    "includeCodeBlocks",
                    "includeCodeMacros",
                    "linkAttributes",
                    "tagFilters",
                    "untitledXrefs",
                    "xrefTitles"
            );

    private static final List<List<String>> CODE_LANGUAGES = List
            .of(
                    List
                            .of(
                                    "java",
                                    "java",
                                    "Java"
                            ),
                    List
                            .of(
                                    "kotlin",
                                    "kt",
                                    "Kotlin"
                            )
            );

    private final AtomicReference<Engine> engine = new AtomicReference<>();

    /** asciidoctorj jar manifest의 Implementation-Version, 없으면 시작 실패 */
    public static String asciidoctorjVersion() {

        String version = AsciidoctorJRuby.class
                .getPackage()
                .getImplementationVersion();

        if (version == null) {
            throw new IllegalStateException("asciidoctorj Implementation-Version is missing from its manifest");
        }

        return version;
    }

    /** 실행 classpath의 JRuby 버전, 컴파일 시 상수가 박히지 않게 리플렉션으로 읽고 JRuby를 띄우지 않음 */
    public static String jrubyVersion() {

        try {
            return String
                    .valueOf(Class
                            .forName("org.jruby.runtime.Constants")
                            .getField("VERSION")
                            .get(null));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "JRuby version is not readable",
                    exception
            );
        }
    }

    /**
     * 원본 하나의 HTML과 판정 재료, includes는 같은 Build의 include와 xref 제목 재료인 쪽이고 key는 commit 고정 원본 URL
     * 전역 잠금이라 병렬 parse가 필요해지면 엔진을 호출 단위로 나눔
     */
    public synchronized AsciidocHtml html(
            SourceDocument source,
            AsciidocRules rules,
            Map<String, SourceDocument> includes
    ) {

        String text = new String(
                source
                        .rawBytes(),
                StandardCharsets.UTF_8
        );
        Call call = new Call(
                rules,
                includes,
                source
                        .finalUrl()
        );

        if (call.pagePath == null) {
            call.unresolved
                    .add("document URL is outside " + rules
                            .antoraModulesPath());

            return result(
                    "",
                    call,
                    text
            );
        }

        Engine current = engine
                .updateAndGet(existing -> existing == null ? new Engine() : existing);
        current
                .attach(call);

        try {
            Document document = current.asciidoctor
                    .load(
                            preprocess(
                                    text,
                                    call
                            ),
                            Options
                                    .builder()
                                    .safe(SafeMode.SECURE)
                                    .standalone(false)
                                    .attributes(attributes(
                                            text,
                                            source
                                                    .exactVersion(),
                                            call
                                    ))
                                    .build()
                    );
            String body = document
                    .convert();
            String html = postprocess(
                    document
                            .getDoctitle(),
                    body,
                    call
            );

            log
                    .info(
                            "asciidoc {} counts {}",
                            source
                                    .finalUrl(),
                            call.counts
                    );

            return result(
                    html,
                    call,
                    text
            );
        } catch (RuntimeException exception) {
            call.unresolved
                    .add("conversion failed: " + exception
                            .getClass()
                            .getName());

            return result(
                    "",
                    call,
                    text
            );
        } finally {
            current
                    .attach(null);
        }
    }

    /** 엔진 종료, 다음 호출은 엔진을 새로 만듦 */
    @Override
    public synchronized void close() {

        Engine current = engine
                .getAndSet(null);

        if (current != null) {
            current.asciidoctor
                    .close();
        }
    }

    // AsciiDoc 태그 규칙으로 거른 글, 지정 태그 없음과 닫히지 않거나 어긋난 태그는 null, spec이 null이면 원문
    static String filterTags(
            String source,
            String spec
    ) {

        if (spec == null) {
            return source;
        }

        Map<String, Boolean> tags = new LinkedHashMap<>();

        for (String tag : spec
                .split("[;,]")) {
            if (tag
                    .isEmpty() || tag
                            .equals("!")) {
                continue;
            }

            if (tag
                    .startsWith("!")) {
                tags
                        .put(
                                tag
                                        .substring(1),
                                false
                        );
            } else {
                tags
                        .put(
                                tag,
                                true
                        );
            }
        }

        Boolean wildcard = null;
        boolean baseSelect;

        if (tags
                .containsKey("**")) {
            baseSelect = tags
                    .remove("**");

            if (tags
                    .containsKey("*")) {
                wildcard = tags
                        .remove("*");
            } else if (!baseSelect && !tags
                    .isEmpty() && !tags
                            .values()
                            .iterator()
                            .next()) {
                wildcard = true;
            }
        } else if (tags
                .containsKey("*")) {
            boolean first = tags
                    .keySet()
                    .iterator()
                    .next()
                    .equals("*");
            wildcard = tags
                    .remove("*");
            baseSelect = first && !wildcard;
        } else {
            baseSelect = !tags
                    .containsValue(true);
        }

        Deque<String> openTags = new ArrayDeque<>();
        Deque<Boolean> openSelects = new ArrayDeque<>();
        Set<String> selected = new HashSet<>();
        StringBuilder out = new StringBuilder();
        boolean select = baseSelect;

        for (String line : source
                .split("(?<=\n)")) {
            String content = line
                    .stripTrailing();
            Matcher directive = TAG_DIRECTIVE
                    .matcher(content);

            if (!directive
                    .find()) {
                if (select) {
                    out
                            .append(line);
                }

                continue;
            }

            String tag = directive
                    .group(2);

            if (directive
                    .group(1)
                    .equals("end")) {
                if (tag
                        .equals(openTags
                                .peek())) {
                    openTags
                            .pop();
                    openSelects
                            .pop();
                    select = openSelects
                            .isEmpty() ? baseSelect : openSelects
                                    .peek();
                } else if (tags
                        .containsKey(tag)) {
                    return null;
                }
            } else if (tags
                    .containsKey(tag)) {
                select = tags
                        .get(tag);

                if (select) {
                    selected
                            .add(tag);
                }

                openTags
                        .push(tag);
                openSelects
                        .push(select);
            } else if (wildcard != null) {
                select = !openTags
                        .isEmpty() && !select ? false : wildcard;
                openTags
                        .push(tag);
                openSelects
                        .push(select);
            }
        }

        boolean missing = tags
                .entrySet()
                .stream()
                .anyMatch(entry -> entry
                        .getValue() && !selected
                                .contains(entry
                                        .getKey()));

        return missing || !openTags
                .isEmpty() ? null : out
                        .toString();
    }

    // Antora resource id의 저장소 경로, 문법 밖, 모르는 family, 두 뿌리 밖으로 나가면 null
    static String resourcePath(
            AsciidocRules rules,
            String id,
            String module
    ) {

        Matcher matcher = RESOURCE_ID
                .matcher(id);

        if (!matcher
                .matches()) {
            return null;
        }

        String owner = matcher
                .group(1) == null ? module : matcher
                        .group(1);
        String family = matcher
                .group(2);
        String base = family
                .equals("example") && owner
                        .equals("ROOT") ? rules
                                .rootExamplesPath() : rules
                                        .antoraModulesPath() + owner + "/" + family + "s/";
        String path = normalize(base + matcher
                .group(3));

        if (path == null || !path
                .startsWith(rules
                        .rootExamplesPath()) && !path
                                .startsWith(rules
                                        .antoraModulesPath())) {
            return null;
        }

        return path;
    }

    // / 로 나눈 경로의 . 과 .. 정리, 뿌리 위로 나가거나 절대 경로면 null
    private static String normalize(String path) {

        if (path
                .startsWith("/")) {
            return null;
        }

        Deque<String> parts = new ArrayDeque<>();

        for (String part : path
                .split("/")) {
            if (part
                    .isEmpty() || part
                            .equals(".")) {
                continue;
            }

            if (!part
                    .equals("..")) {
                parts
                        .addLast(part);

                continue;
            }

            if (parts
                    .isEmpty()) {
                return null;
            }

            parts
                    .removeLast();
        }

        return String
                .join(
                        "/",
                        parts
                );
    }

    // 원문 전처리, configprops 블록은 source 블록, 같은 module xref 접두 제거와 빈 글 채우기, 다른 module xref는 링크 글
    private static String preprocess(
            String text,
            Call call
    ) {

        String local = CONFIGPROPS
                .matcher(text)
                .replaceAll("[source,$1]")
                .replace(
                        "xref:" + call.module + ":",
                        "xref:"
                );
        Matcher matcher = CROSS_MODULE_XREF
                .matcher(local);
        StringBuilder out = new StringBuilder();

        while (matcher
                .find()) {
            String label = matcher
                    .group(3)
                    .isEmpty() ? matcher
                            .group(1) + ":" + matcher
                                    .group(2) : matcher
                                            .group(3);

            matcher
                    .appendReplacement(
                            out,
                            Matcher
                                    .quoteReplacement(label)
                    );
            call
                    .count(
                            "crossModuleXrefs",
                            1
                    );
        }

        matcher
                .appendTail(out);

        return titled(
                out
                        .toString(),
                text,
                call
        );
    }

    // 같은 module xref의 빈 글을 대상 섹션 제목이나 쪽 제목으로, 대상이나 제목을 못 찾으면 그대로 두고 셈
    private static String titled(
            String local,
            String text,
            Call call
    ) {

        Matcher matcher = SAME_MODULE_XREF
                .matcher(local);
        StringBuilder out = new StringBuilder();

        while (matcher
                .find()) {
            String path = matcher
                    .group(1);
            String id = matcher
                    .group(2);
            String title = call
                    .title(
                            path,
                            id,
                            text
                    );

            if (title == null) {
                call
                        .count(
                                "untitledXrefs",
                                1
                        );

                continue;
            }

            matcher
                    .appendReplacement(
                            out,
                            Matcher
                                    .quoteReplacement("xref:" + path + (id == null ? "" : "#" + id) + "[" + title
                                            .replace(
                                                    "]",
                                                    "\\]"
                                            ) + "]")
                    );
            call
                    .count(
                            "xrefTitles",
                            1
                    );
        }

        matcher
                .appendTail(out);

        return out
                .toString();
    }

    // 원문에서 섹션 id 바로 다음 제목 줄의 글, id가 null이면 첫 쪽 제목 줄의 글, 없으면 null
    private static String heading(
            String text,
            String id
    ) {

        List<String> lines = text
                .lines()
                .toList();
        int count = lines
                .size();

        for (int index = 0; index < count; index++) {
            String line = lines
                    .get(index);

            if (id == null) {
                if (line
                        .startsWith("= ")) {
                    return line
                            .substring(2)
                            .strip();
                }

                continue;
            }

            Matcher anchor = ANCHOR_LINE
                    .matcher(line);

            if (!anchor
                    .matches() || !anchor
                            .group(1)
                            .equals(id)) {
                continue;
            }

            Matcher heading = HEADING_LINE
                    .matcher(index + 1 < count ? lines
                            .get(index + 1) : "");

            return heading
                    .matches() ? heading
                            .group(1) : null;
        }

        return null;
    }

    // 결정적 속성, 링크 속성 표지, 출처 고정 속성을 모두 소프트 세트로
    private static Attributes attributes(
            String text,
            String exactVersion,
            Call call
    ) {

        Map<String, String> values = new LinkedHashMap<>();
        values
                .put(
                        "reproducible",
                        ""
                );

        for (String name : List
                .of(
                        "docdate",
                        "localdate"
                )) {
            values
                    .put(
                            name,
                            FIXED_DATE
                    );
        }

        for (String name : List
                .of(
                        "doctime",
                        "localtime"
                )) {
            values
                    .put(
                            name,
                            FIXED_TIME
                    );
        }

        for (String name : List
                .of(
                        "docdatetime",
                        "localdatetime"
                )) {
            values
                    .put(
                            name,
                            FIXED_DATE + " " + FIXED_TIME
                    );
        }

        for (String name : List
                .of(
                        "docyear",
                        "localyear"
                )) {
            values
                    .put(
                            name,
                            FIXED_YEAR
                    );
        }

        values
                .put(
                        "outfilesuffix",
                        ".adoc"
                );
        values
                .put(
                        "relfileprefix",
                        "../"
                                .repeat((int) call.pagePath
                                        .chars()
                                        .filter(character -> character == '/')
                                        .count())
                );
        values
                .putAll(new TreeMap<>(call.rules
                        .attributes()));
        values
                .put(
                        call.rules
                                .exactVersionAttribute(),
                        exactVersion
                );

        for (String name : linkAttributes(
                text,
                call.rules
        )) {
            values
                    .put(
                            name,
                            UNRESOLVED_LINK + name
                    );
        }

        AttributesBuilder builder = Attributes
                .builder();

        for (Map.Entry<String, String> entry : values
                .entrySet()) {
            builder
                    .attribute(
                            entry
                                    .getKey(),
                            entry
                                    .getValue() + "@"
                    );
        }

        return builder
                .build();
    }

    // 원문에서 링크 속성 접두로 시작하는 속성 참조 이름, 이름 순
    private static Set<String> linkAttributes(
            String text,
            AsciidocRules rules
    ) {

        Pattern pattern = Pattern
                .compile("\\{((?:" + rules
                        .linkAttributePrefixes()
                        .stream()
                        .map(Pattern::quote)
                        .collect(Collectors
                                .joining("|")) + ")[\\w-]*)\\}");
        Matcher matcher = pattern
                .matcher(text);
        Set<String> names = new TreeSet<>();

        while (matcher
                .find()) {
            names
                    .add(matcher
                            .group(1));
        }

        return names;
    }

    // 잔여 검사, 링크 표지 해제, 문서 틀로 감싼 HTML
    private static String postprocess(
            String title,
            String body,
            Call call
    ) {

        org.jsoup.nodes.Document fragment = Jsoup
                .parseBodyFragment(body);
        fragment
                .outputSettings()
                .prettyPrint(false);
        Element root = fragment
                .body();

        scanResidue(
                root,
                call
        );
        unwrapLinks(
                root,
                call
        );

        String heading = title == null ? "" : "<h1 id=\"page-title\">" + title + "</h1>";

        return "<article class=\"doc\">" + heading + root
                .html() + "</article>";
    }

    // pre, code 밖 텍스트의 매크로, 속성 모양 잔여 중 허용 목록 밖을 판정 재료로
    private static void scanResidue(
            Element root,
            Call call
    ) {

        for (Element element : root
                .getAllElements()) {
            if (element
                    .closest("pre, code") != null) {
                continue;
            }

            for (TextNode node : element
                    .textNodes()) {
                for (Pattern pattern : List
                        .of(
                                MACRO_RESIDUE,
                                ATTRIBUTE_RESIDUE
                        )) {
                    Matcher matcher = pattern
                            .matcher(node
                                    .getWholeText());

                    while (matcher
                            .find()) {
                        String residue = matcher
                                .group();

                        if (!call.rules
                                .residueAllowlist()
                                .contains(call.pagePath + "|" + residue)) {
                            call.unresolved
                                    .add("residue " + residue);
                        }
                    }
                }
            }
        }
    }

    // 링크 속성 표지 링크를 글만 남기고 풂, 글이 scheme을 숨긴 주소까지 포함해 주소 그대로면 원래 속성 참조 글자로
    private static void unwrapLinks(
            Element root,
            Call call
    ) {

        for (Element link : root
                .getElementsByTag("a")) {
            String href = link
                    .attr("href");

            if (!href
                    .startsWith(UNRESOLVED_LINK)) {
                continue;
            }

            call
                    .count(
                            "linkAttributes",
                            1
                    );

            String shown = link
                    .text();

            if (!shown
                    .equals(href) && !shown
                            .equals(href
                                    .substring("https://"
                                            .length()))) {
                link
                        .unwrap();

                continue;
            }

            String reference = href
                    .substring(UNRESOLVED_LINK
                            .length());
            int slash = reference
                    .indexOf('/');
            String restored = slash < 0 ? "{" + reference + "}" : "{" + reference
                    .substring(
                            0,
                            slash
                    ) + "}" + reference
                            .substring(slash);

            link
                    .replaceWith(new TextNode(restored));
            call
                    .count(
                            "bareLinkAttributes",
                            1
                    );
        }
    }

    // 원문 줄 표와 호출 기록으로 만든 결과
    private static AsciidocHtml result(
            String html,
            Call call,
            String text
    ) {

        Map<String, Integer> anchorLines = new HashMap<>();
        int titleLine = 0;
        String[] lines = text
                .split(
                        "\n",
                        -1
                );
        int count = lines.length;

        for (int index = 0; index < count; index++) {
            Matcher matcher = ANCHOR_LINE
                    .matcher(lines[index]);

            if (matcher
                    .matches()) {
                anchorLines
                        .putIfAbsent(
                                matcher
                                        .group(1),
                                index + 1
                        );
            }

            if (titleLine == 0 && lines[index]
                    .startsWith("= ")) {
                titleLine = index + 1;
            }
        }

        return new AsciidocHtml(
                html,
                List
                        .copyOf(call.resolved
                                .values()),
                List
                        .copyOf(call.titles
                                .values()),
                call.unresolved,
                anchorLines,
                titleLine,
                call.counts
        );
    }

    // 코드 블록 하나의 HTML
    private static String listing(
            String language,
            String code
    ) {

        return "<div class=\"listingblock\"><div class=\"content\"><pre class=\"highlight\"><code class=\"language-" + language
                + "\" data-lang=\"" + language + "\">" + Entities
                        .escape(code) + "</code></pre></div></div>";
    }

    // 라벨과 패널 HTML 목록으로 만든 탭 그룹 HTML
    private static String tabs(
            String groupId,
            List<List<String>> panels
    ) {

        StringBuilder html = new StringBuilder();
        StringBuilder bodies = new StringBuilder();
        Map<String, Integer> keys = new HashMap<>();

        html
                .append("<div id=\"")
                .append(groupId)
                .append("\" class=\"openblock tabs\"><div class=\"content\"><div class=\"ulist tablist\"><ul>");

        for (List<String> panel : panels) {
            String label = panel
                    .get(0);
            String key = label
                    .toLowerCase(Locale.ROOT)
                    .replaceAll(
                            "[^a-z0-9]+",
                            "_"
                    );
            int seen = keys
                    .merge(
                            key,
                            1,
                            Integer::sum
                    );
            String tabId = groupId + "_" + key + (seen > 1 ? "_" + seen : "");

            html
                    .append("<li id=\"")
                    .append(tabId)
                    .append("\" class=\"tab\"><p>")
                    .append(label)
                    .append("</p></li>");
            bodies
                    .append("<div id=\"")
                    .append(tabId)
                    .append("--panel\" class=\"tabpanel\" aria-labelledby=\"")
                    .append(tabId)
                    .append("\">")
                    .append(panel
                            .get(1))
                    .append("</div>");
        }

        return html
                .append("</ul></div>")
                .append(bodies)
                .append("</div></div>")
                .toString();
    }

    // 확장 속성 중 tag, tags 밖 이름, 없으면 null
    private static String unknownAttribute(Map<String, Object> attributes) {

        for (Object key : attributes
                .keySet()) {
            if (!"tag"
                    .equals(key) && !"tags"
                            .equals(key)) {
                return String
                        .valueOf(key);
            }
        }

        return null;
    }

    // 매크로보다 먼저 돈 치환의 문자 참조를 글자로 되돌린 글, 말줄임표 치환은 원래 점 셋으로
    private static String plain(String text) {

        return Parser
                .unescapeEntities(
                        text,
                        false
                )
                .replace(
                        "\u2026\u200B",
                        "..."
                );
    }

    // tag가 tags보다 우선하는 태그 지정, 없으면 null
    private static String tagSpec(Map<String, Object> attributes) {

        Object tag = attributes
                .get("tag");

        if (tag != null) {
            return String
                    .valueOf(tag);
        }

        Object tags = attributes
                .get("tags");

        return tags == null ? null : String
                .valueOf(tags);
    }

    // 호출 하나의 입력과 수집기, 문서 URL에서 commit 뿌리, module, 쪽 경로를 나눔
    private static final class Call {

        private final AsciidocRules rules;
        private final Map<String, SourceDocument> includes;
        private final String commitRoot;
        private final String module;
        private final String pagePath;
        private final List<String> unresolved = new ArrayList<>();
        private final Map<String, ResolvedInclude> resolved = new LinkedHashMap<>();
        private final Map<String, TitleSource> titles = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new TreeMap<>();
        private int tabGroups;

        // 문서 URL이 Antora module 경로 밖이면 pagePath는 null
        private Call(
                AsciidocRules rules,
                Map<String, SourceDocument> includes,
                String finalUrl
        ) {

            this.rules = rules;
            this.includes = includes;

            for (String key : COUNT_KEYS) {
                counts
                        .put(
                                key,
                                0
                        );
            }

            int start = finalUrl
                    .indexOf(rules
                            .antoraModulesPath());
            String rest = start < 0 ? "" : finalUrl
                    .substring(start + rules
                            .antoraModulesPath()
                            .length());
            int slash = rest
                    .indexOf('/');
            String owner = slash < 0 ? null : rest
                    .substring(
                            0,
                            slash
                    );
            String pages = owner + "/pages/";

            this.commitRoot = start < 0 ? null : finalUrl
                    .substring(
                            0,
                            start
                    );
            this.module = owner;
            this.pagePath = owner == null || !rest
                    .startsWith(pages) ? null : rest
                            .substring(pages
                                    .length());
        }

        // 개수 더하기
        private void count(
                String key,
                int amount
        ) {

            counts
                    .merge(
                            key,
                            amount,
                            Integer::sum
                    );
        }

        // 같은 module 쪽 경로와 섹션 id의 제목, 자기 쪽은 원문에서, 다른 쪽은 같은 Build 입력에서 찾아 기록, 못 찾으면 null
        private String title(
                String path,
                String id,
                String text
        ) {

            String target = normalize(path);

            if (target == null) {
                return null;
            }

            if (target
                    .equals(pagePath)) {
                return heading(
                        text,
                        id
                );
            }

            String url = commitRoot + rules
                    .antoraModulesPath() + module + "/pages/" + target;
            SourceDocument page = includes
                    .get(url);

            if (page == null) {
                return null;
            }

            String title;

            try {
                title = heading(
                        TextNormalizer
                                .decodeUtf8(page
                                        .rawBytes()),
                        id
                );
            } catch (CharacterCodingException exception) {
                return null;
            }

            if (title != null) {
                titles
                        .putIfAbsent(
                                url,
                                new TitleSource(
                                        url,
                                        page
                                                .rawSha256()
                                )
                        );
            }

            return title;
        }

        // 봉인된 include 하나를 태그로 거른 글과 사용 기록, 없으면 null, 읽지 못하면 판정 재료와 빈 글
        private String include(
                String path,
                String tags
        ) {

            String url = commitRoot + path;
            SourceDocument include = includes
                    .get(url);

            if (include == null) {
                return null;
            }

            String filtered;

            try {
                filtered = filterTags(
                        TextNormalizer
                                .decodeUtf8(include
                                        .rawBytes()),
                        tags
                );
            } catch (CharacterCodingException exception) {
                unresolved
                        .add("include " + url + " is not valid UTF-8");

                return "";
            }

            if (filtered == null) {
                unresolved
                        .add("tags " + tags + " not found in " + url);

                return "";
            }

            if (tags != null) {
                count(
                        "tagFilters",
                        1
                );
            }

            resolved
                    .putIfAbsent(
                            url + "|" + tags,
                            new ResolvedInclude(
                                    url,
                                    include
                                            .rawSha256(),
                                    tags
                            )
                    );

            return filtered;
        }

        // 문서 안 다음 탭 그룹 id
        private String nextTabGroupId() {

            tabGroups++;

            return "_tabs_" + tabGroups;
        }
    }

    // Asciidoctor 인스턴스와 등록한 확장 다섯, 호출별 상태가 필요한 확장에 attach로 꽂고 비움
    private static final class Engine {

        private final Asciidoctor asciidoctor;
        private final IncludeCodeMacro includeCode = new IncludeCodeMacro();
        private final StoredInclude storedInclude = new StoredInclude();
        private final JavadocMacro javadoc = new JavadocMacro();
        private final ConfigpropMacro configprop = new ConfigpropMacro();
        private final TabsTreeprocessor tabs = new TabsTreeprocessor();
        private Call current;

        // 엔진 생성과 확장, WARN 이상 로그 수집기 등록
        private Engine() {

            asciidoctor = Asciidoctor.Factory
                    .create();
            asciidoctor
                    .javaExtensionRegistry()
                    .blockMacro(includeCode)
                    .includeProcessor(storedInclude)
                    .inlineMacro(javadoc)
                    .inlineMacro(configprop)
                    .treeprocessor(tabs);
            asciidoctor
                    .registerLogHandler(record -> {
                        Call call = current;

                        if (call != null && record
                                .getSeverity()
                                .ordinal() >= Severity.WARN
                                        .ordinal()) {
                            call.unresolved
                                    .add("asciidoctor " + record
                                            .getSeverity() + ": " + record
                                                    .getMessage());
                        }
                    });
        }

        // 엔진과 상태를 쓰는 확장에 호출 상태 꽂기, null이면 비움
        private void attach(Call call) {

            current = call;
            includeCode.call = call;
            storedInclude.call = call;
            tabs.call = call;
        }
    }

    // include-code 블록 매크로, 섹션 id와 언어별 예시 경로로 봉인된 include를 찾아 코드 블록이나 언어 탭
    @Name("include-code")
    private static final class IncludeCodeMacro extends BlockMacroProcessor {

        private Call call;

        /** 매크로 하나의 코드 블록, 못 찾으면 판정 재료와 빈 문단 */
        @Override
        public StructuralNode process(
                StructuralNode parent,
                String target,
                Map<String, Object> attributes
        ) {

            call
                    .count(
                            "includeCodeMacros",
                            1
                    );

            String unknown = unknownAttribute(attributes);
            String sectionId = sectionId(parent);

            if (unknown != null || sectionId == null) {
                call.unresolved
                        .add("include-code " + target + (unknown != null ? " has unknown attribute " + unknown : " has no section id"));

                return empty(parent);
            }

            String tags = tagSpec(attributes);
            String directory = sectionId
                    .replace(
                            "-",
                            ""
                    )
                    .replace(
                            '.',
                            '/'
                    );
            List<List<String>> panels = new ArrayList<>();

            for (List<String> language : CODE_LANGUAGES) {
                String languageRoot = call.rules
                        .includeCodePath()
                        .replace(
                                "{lang}",
                                language
                                        .get(0)
                        );
                String path = normalize(languageRoot + directory + "/" + target + "." + language
                        .get(1));
                String code = path == null || !path
                        .startsWith(languageRoot) ? null : call
                                .include(
                                        path,
                                        tags
                                );

                if (code != null) {
                    panels
                            .add(List
                                    .of(
                                            language
                                                    .get(2),
                                            listing(
                                                    language
                                                            .get(0),
                                                    code
                                            )
                                    ));
                }
            }

            if (panels
                    .isEmpty()) {
                call.unresolved
                        .add("include-code " + target + " not found for section " + sectionId);

                return empty(parent);
            }

            call
                    .count(
                            "includeCodeBlocks",
                            panels
                                    .size()
                    );

            String html = panels
                    .size() == 1 ? panels
                            .getFirst()
                            .get(1) : tabs(
                                    call
                                            .nextTabGroupId(),
                                    panels
                            );

            return createBlock(
                    parent,
                    "pass",
                    html
            );
        }

        // 가장 가까운 id 있는 상위 섹션이나 문서의 id, 없으면 null
        private static String sectionId(StructuralNode parent) {

            ContentNode node = parent;

            while (node != null) {
                if (node instanceof StructuralNode structural && ("section"
                        .equals(structural
                                .getContext()) || "document"
                                        .equals(structural
                                                .getContext())) && structural
                                                        .getId() != null) {
                    return structural
                            .getId();
                }

                node = node
                        .getParent();
            }

            return null;
        }

        // 보류될 문서에 남기는 빈 문단
        private StructuralNode empty(StructuralNode parent) {

            return createBlock(
                    parent,
                    "paragraph",
                    ""
            );
        }
    }

    // 모든 include 대상을 봉인된 include에서만 찾음, 생성 partial은 표지 한 줄
    private static final class StoredInclude extends IncludeProcessor {

        private Call call;

        /** 파일, URI를 읽지 않도록 모든 대상을 이 확장이 처리 */
        @Override
        public boolean handles(String target) {

            return true;
        }

        /** include 하나의 글을 읽기 위치에 넣음, 못 찾으면 판정 재료와 빈 글 */
        @Override
        public void process(
                Document document,
                PreprocessorReader reader,
                String target,
                Map<String, Object> attributes
        ) {

            String content = content(
                    target,
                    attributes
            );

            reader
                    .pushInclude(
                            content,
                            target,
                            target,
                            1,
                            attributes
                    );
        }

        // 대상 하나의 글
        private String content(
                String target,
                Map<String, Object> attributes
        ) {

            String unknown = unknownAttribute(attributes);

            if (unknown != null) {
                call.unresolved
                        .add("include " + target + " has unknown attribute " + unknown);

                return "";
            }

            String path = resourcePath(
                    call.rules,
                    target,
                    call.module
            );
            String text = path == null ? null : call
                    .include(
                            path,
                            tagSpec(attributes)
                    );

            if (text != null) {
                return text;
            }

            boolean generated = call.rules
                    .generatedIncludes()
                    .stream()
                    .anyMatch(target::startsWith);

            if (!generated) {
                call.unresolved
                        .add("include " + target + " not found");

                return "";
            }

            call
                    .count(
                            "generatedMarkers",
                            1
                    );

            return "[DocLens] generated content not in source: `" + target + "`\n";
        }
    }

    // javadoc 인라인 매크로, 링크 없이 클래스 이름 인라인 코드
    @Name("javadoc")
    private static final class JavadocMacro extends InlineMacroProcessor {

        /** 대상의 표시 글을 인라인 코드로 */
        @Override
        public PhraseNode process(
                StructuralNode parent,
                String target,
                Map<String, Object> attributes
        ) {

            return createPhraseNode(
                    parent,
                    "quoted",
                    Entities
                            .escape(shown(
                                    plain(target),
                                    attributes
                            )),
                    Map
                            .of(),
                    Map
                            .of(
                                    "type",
                                    ":monospaced"
                            )
            );
        }

        // 준 글, 전체 이름, 클래스 이름 순으로 정한 표시 글, 멤버가 있으면 단순 매개변수 이름을 붙임
        private static String shown(
                String target,
                Map<String, Object> attributes
        ) {

            Object given = attributes
                    .get("1");

            if (given != null) {
                return plain(String
                        .valueOf(given));
            }

            String reference = target
                    .substring(target
                            .lastIndexOf('/') + 1);
            int hash = reference
                    .indexOf('#');
            String type = hash < 0 ? reference : reference
                    .substring(
                            0,
                            hash
                    );
            Object format = attributes
                    .get("format");
            String name = "full"
                    .equals(format) ? type : Arrays
                            .stream(type
                                    .split("\\."))
                            .dropWhile(segment -> !segment
                                    .isEmpty() && Character
                                            .isLowerCase(segment
                                                    .charAt(0)))
                            .collect(Collectors
                                    .joining("."));
            String shown = ("annotation"
                    .equals(format) ? "@" : "") + name
                            .replace(
                                    '$',
                                    '.'
                            );

            if (hash < 0) {
                return shown;
            }

            String member = reference
                    .substring(hash + 1);
            Matcher parameters = MEMBER_PARAMETERS
                    .matcher(member);

            if (!parameters
                    .find()) {
                return shown + "." + member;
            }

            String simple = Arrays
                    .stream(parameters
                            .group(1)
                            .split(","))
                    .map(String::strip)
                    .filter(parameter -> !parameter
                            .isEmpty())
                    .map(JavadocMacro::simpleName)
                    .collect(Collectors
                            .joining(", "));

            return shown + "." + member
                    .substring(
                            0,
                            parameters
                                    .start()
                    ) + "(" + simple + ")";
        }

        // 매개변수 타입의 단순 이름, 가변 인자의 점 셋은 그대로
        private static String simpleName(String parameter) {

            String varargs = parameter
                    .endsWith("...") ? "..." : "";
            String type = parameter
                    .substring(
                            0,
                            parameter
                                    .length() - varargs
                                            .length()
                    );

            return type
                    .substring(type
                            .lastIndexOf('.') + 1)
                    .replace(
                            '$',
                            '.'
                    ) + varargs;
        }
    }

    // configprop 인라인 매크로, 속성 이름이나 환경 변수 이름 인라인 코드
    @Name("configprop")
    private static final class ConfigpropMacro extends InlineMacroProcessor {

        /** 속성 이름 인라인 코드, format=envvar면 환경 변수 이름 */
        @Override
        public PhraseNode process(
                StructuralNode parent,
                String target,
                Map<String, Object> attributes
        ) {

            String name = plain(target);
            String shown = "envvar"
                    .equals(attributes
                            .get("format")) ? name
                                    .toUpperCase(Locale.ROOT)
                                    .replace(
                                            '.',
                                            '_'
                                    )
                                    .replace(
                                            "-",
                                            ""
                                    ) : name;

            return createPhraseNode(
                    parent,
                    "quoted",
                    Entities
                            .escape(shown),
                    Map
                            .of(),
                    Map
                            .of(
                                    "type",
                                    ":monospaced"
                            )
            );
        }
    }

    // tabs style 예제 블록 안 정의 목록 하나를 탭 그룹 HTML로
    private static final class TabsTreeprocessor extends Treeprocessor {

        private Call call;

        /** 문서 전체의 탭 블록 교체 */
        @Override
        public Document process(Document document) {

            replace(document);

            return document;
        }

        // 자식 블록을 차례로 보며 탭 블록 교체, 나머지는 안으로 들어감
        private void replace(StructuralNode node) {

            List<StructuralNode> blocks = node
                    .getBlocks();

            if (blocks == null) {
                return;
            }

            int count = blocks
                    .size();

            for (int index = 0; index < count; index++) {
                StructuralNode block = blocks
                        .get(index);

                if (!isTabs(block)) {
                    replace(block);

                    continue;
                }

                DescriptionList list = (DescriptionList) block
                        .getBlocks()
                        .getFirst();
                List<List<String>> panels = new ArrayList<>();

                for (DescriptionListEntry entry : list
                        .getItems()) {
                    panels
                            .add(List
                                    .of(
                                            entry
                                                    .getTerms()
                                                    .getFirst()
                                                    .getText(),
                                            panel(entry
                                                    .getDescription())
                                    ));
                }

                blocks
                        .set(
                                index,
                                createBlock(
                                        node,
                                        "pass",
                                        tabs(
                                                call
                                                        .nextTabGroupId(),
                                                panels
                                        )
                                )
                        );
            }
        }

        // tabs style 예제 블록이고 자식이 정의 목록 하나인지 여부
        private static boolean isTabs(StructuralNode block) {

            return "example"
                    .equals(block
                            .getContext()) && "tabs"
                                    .equals(block
                                            .getStyle()) && block
                                                    .getBlocks()
                                                    .size() == 1 && block
                                                            .getBlocks()
                                                            .getFirst() instanceof DescriptionList;
        }

        // 정의 항목 설명의 글과 블록 HTML
        private static String panel(ListItem description) {

            if (description == null) {
                return "";
            }

            StringBuilder html = new StringBuilder();

            if (description
                    .hasText() && !description
                            .getText()
                            .isBlank()) {
                html
                        .append("<div class=\"paragraph\"><p>")
                        .append(description
                                .getText())
                        .append("</p></div>");
            }

            for (StructuralNode child : description
                    .getBlocks()) {
                html
                        .append(child
                                .convert());
            }

            return html
                    .toString();
        }
    }
}
