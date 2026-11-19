package io.github.kangwooju.doclens.pipeline.parse.helper;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;
import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.ExtractedDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourcePointer;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredSection;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabVariant;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableCell;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableData;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableRow;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

/**
 * 본문 root를 DOM 순서로 순회해 섹션, 블록, 탭 그룹 생성
 * 제외 요소는 DOM을 고치지 않고 건너뜀, Asciidoctor HTML5 클래스로 블록 종류 판정
 */
@Component
public class BlockExtractHelper {

    /** 코드 블록과 탭 변형 하나의 최대 UTF-8 바이트 */
    public static final int MAX_CODE_BYTES = 1024 * 1024;

    private static final String NESTED_BLOCKS = "div.listingblock, div.literalblock, div.admonitionblock, div.openblock.tabs, table.tableblock";

    private static final String LIST_BLOCKS = "div.ulist, div.olist, div.dlist, div.colist, div.hdlist, div.qlist";

    private static final Set<String> SILENT_TAGS = Set
            .of(
                    "script",
                    "style",
                    "noscript",
                    "template"
            );

    private static final int NONE = 0;

    private static final int SPACE = 1;

    private static final int BREAK = 2;

    /** 본문 root 아래 순회, 탭 기본 변형은 정하지 않은 결과 */
    public ExtractedDocument extract(
            Element root,
            ParserProfile profile,
            String documentId,
            String finalUrl
    ) {

        Walk walk = new Walk(
                root,
                profile,
                documentId,
                finalUrl
        );
        walk
                .visit(
                        root,
                        Sink
                                .section()
                );

        return walk
                .result();
    }

    // 요소의 클래스 포함 여부
    private static boolean is(
            Element element,
            String tag,
            String className
    ) {

        return element
                .normalName()
                .equals(tag) && element
                        .hasClass(className);
    }

    // 블록 종류를 정하는 요소 여부
    private static boolean isBlockElement(Element element) {

        return is(
                element,
                "div",
                "paragraph"
        ) || is(
                element,
                "div",
                "listingblock"
        ) || is(
                element,
                "div",
                "literalblock"
        ) || is(
                element,
                "div",
                "verseblock"
        ) || is(
                element,
                "div",
                "imageblock"
        ) || is(
                element,
                "div",
                "admonitionblock"
        ) || is(
                element,
                "table",
                "tableblock"
        ) || isListElement(element);
    }

    // 목록 블록 여부
    private static boolean isListElement(Element element) {

        return element
                .normalName()
                .equals("div") && (element
                        .hasClass("ulist") || element
                                .hasClass("olist") || element
                                        .hasClass("dlist") || element
                                                .hasClass("colist") || element
                                                        .hasClass("hdlist") || element
                                                                .hasClass("qlist"));
    }

    // 요소 하나만 담은 identity 집합
    private static Set<Element> identitySet() {

        return Collections
                .newSetFromMap(new IdentityHashMap<>());
    }

    // 블록이 쌓이는 곳과 admonition 펼치기 상태
    private static final class Sink {

        private final List<Block> blocks;
        private final boolean panel;
        private final String admonitionType;
        private final Element admonition;
        private final Element admonitionTitle;
        private Walk.TextBuilder pending;

        // 쌓을 곳과 상태 지정
        private Sink(
                List<Block> blocks,
                boolean panel,
                String admonitionType,
                Element admonition,
                Element admonitionTitle
        ) {

            this.blocks = blocks;
            this.panel = panel;
            this.admonitionType = admonitionType;
            this.admonition = admonition;
            this.admonitionTitle = admonitionTitle;
        }

        // 현재 섹션에 쌓는 기본 상태
        static Sink section() {

            return new Sink(
                    null,
                    false,
                    null,
                    null,
                    null
            );
        }

        // 탭 패널 블록 목록에 쌓는 상태
        static Sink panel(List<Block> blocks) {

            return new Sink(
                    blocks,
                    true,
                    null,
                    null,
                    null
            );
        }

        // 같은 곳에 쌓으며 admonition 문단을 모으는 상태
        Sink admonition(
                String type,
                Element element,
                Element title
        ) {

            return new Sink(
                    blocks,
                    panel,
                    type,
                    element,
                    title
            );
        }

        // admonition 문단을 모으는 중인지 여부
        boolean collectsAdmonition() {

            return admonitionType != null;
        }
    }

    // 섹션 하나를 만드는 동안의 값
    private static final class SectionDraft {

        private final String sectionId;
        private final String parentSectionId;
        private final String headingLocator;
        private final String anchor;
        private final List<String> headingPath;
        private final int level;
        private final int ordinal;
        private final List<Integer> ordinalPath;
        private final List<Block> blocks = new ArrayList<>();

        // 섹션 값 지정
        private SectionDraft(
                String sectionId,
                String parentSectionId,
                String headingLocator,
                String anchor,
                List<String> headingPath,
                int level,
                int ordinal,
                List<Integer> ordinalPath
        ) {

            this.sectionId = sectionId;
            this.parentSectionId = parentSectionId;
            this.headingLocator = headingLocator;
            this.anchor = anchor;
            this.headingPath = headingPath;
            this.level = level;
            this.ordinal = ordinal;
            this.ordinalPath = ordinalPath;
        }

        // 불변 섹션으로 변환
        StructuredSection toSection() {

            return new StructuredSection(
                    sectionId,
                    parentSectionId,
                    headingLocator,
                    anchor,
                    headingPath,
                    level,
                    ordinal,
                    blocks
            );
        }
    }

    // 문서 하나의 순회 상태
    private static final class Walk {

        private final Element root;
        private final ParserProfile profile;
        private final String documentId;
        private final URI base;
        private final Set<Element> excluded = identitySet();
        private final Set<Element> headings = identitySet();
        private final List<SectionDraft> sections = new ArrayList<>();
        private final Deque<SectionDraft> open = new ArrayDeque<>();
        private final Map<String, Integer> headingIds = new HashMap<>();
        private final Map<String, Integer> tabIds = new HashMap<>();
        private final Map<String, Integer> siblings = new HashMap<>();
        private SectionDraft current;
        private int tabGroups;

        // 제외 요소와 heading 요소 미리 찾기
        private Walk(
                Element root,
                ParserProfile profile,
                String documentId,
                String finalUrl
        ) {

            this.root = root;
            this.profile = profile;
            this.documentId = documentId;
            this.base = parseBase(finalUrl);

            for (String selector : profile
                    .excludedSelectors()) {
                excluded
                        .addAll(root
                                .select(selector));
            }

            for (String selector : profile
                    .headingSelectors()) {
                headings
                        .addAll(root
                                .select(selector));
            }

            headings
                    .removeAll(excluded);
            headings
                    .removeIf(heading -> heading
                            .hasClass("discrete"));
        }

        // 링크 해석 기준 URL, 해석할 수 없으면 null
        private static URI parseBase(String finalUrl) {

            try {
                return new URI(finalUrl);
            } catch (URISyntaxException exception) {
                return null;
            }
        }

        // 순회 결과, 섹션과 블록이 없으면 보류
        ExtractedDocument result() {

            if (sections
                    .isEmpty()) {
                throw new DocumentHoldException(
                        HoldReason.TITLE_MISSING,
                        "no heading in body"
                );
            }

            int blockCount = sections
                    .stream()
                    .mapToInt(section -> section.blocks
                            .size())
                    .sum();

            if (blockCount == 0) {
                throw new DocumentHoldException(
                        HoldReason.BODY_EMPTY,
                        "no block in body"
                );
            }

            return new ExtractedDocument(
                    sections
                            .getFirst().headingPath
                            .getLast(),
                    sections
                            .stream()
                            .map(SectionDraft::toSection)
                            .toList()
            );
        }

        // 컨테이너 자식 순회, 블록 사이의 연속 인라인 노드는 문단 하나
        void visit(
                Element container,
                Sink sink
        ) {

            List<Node> run = new ArrayList<>();

            for (Node node : container
                    .childNodes()) {
                if (node instanceof TextNode || node instanceof Element element && isInline(element)) {
                    run
                            .add(node);

                    continue;
                }

                if (!(node instanceof Element element)) {
                    continue;
                }

                flushRun(
                        run,
                        container,
                        sink
                );
                handle(
                        element,
                        sink
                );
            }

            flushRun(
                    run,
                    container,
                    sink
            );
        }

        // 프로필 선택자의 탭 그룹 여부
        private boolean isTabGroup(Element element) {

            return element
                    .is(profile
                            .tabSelectors()
                            .group());
        }

        // 문단에 섞이는 인라인 요소 여부
        private boolean isInline(Element element) {

            return !headings
                    .contains(element) && !isBlockElement(element) && !isTabGroup(element) && element
                            .tag()
                            .isInline() && !SILENT_TAGS
                                    .contains(element
                                            .normalName());
        }

        // 요소 하나 처리
        private void handle(
                Element element,
                Sink sink
        ) {

            if (excluded
                    .contains(element) || SILENT_TAGS
                            .contains(element
                                    .normalName()) || element == sink.admonitionTitle) {
                return;
            }

            if (headings
                    .contains(element)) {
                openSection(element);

                return;
            }

            if (is(
                    element,
                    "div",
                    "paragraph"
            )) {
                paragraph(
                        element,
                        sink
                );
            } else if (is(
                    element,
                    "div",
                    "listingblock"
            ) || is(
                    element,
                    "div",
                    "literalblock"
            )) {
                code(
                        element,
                        sink
                );
            } else if (is(
                    element,
                    "div",
                    "verseblock"
            )) {
                verse(
                        element,
                        sink
                );
            } else if (is(
                    element,
                    "div",
                    "imageblock"
            )) {
                image(
                        element,
                        sink
                );
            } else if (is(
                    element,
                    "div",
                    "admonitionblock"
            )) {
                admonition(
                        element,
                        sink
                );
            } else if (is(
                    element,
                    "table",
                    "tableblock"
            )) {
                table(
                        element,
                        sink
                );
            } else if (isListElement(element)) {
                list(
                        element,
                        sink
                );
            } else if (isTabGroup(element)) {
                tabs(
                        element,
                        sink
                );
            } else {
                visit(
                        element,
                        sink
                );
            }
        }

        // heading이 여는 섹션
        private void openSection(Element heading) {

            int level = heading
                    .normalName()
                    .charAt(1) - '0';

            if (sections
                    .isEmpty() && level != 1) {
                throw new DocumentHoldException(
                        HoldReason.TITLE_MISSING,
                        "first heading is not a level 1 title at " + domPath(heading)
                );
            }
            Set<Element> anchors = identitySet();
            anchors
                    .addAll(heading
                            .select("a.anchor"));
            String text = new TextBuilder(anchors)
                    .appendChildren(heading)
                    .text();

            while (!open
                    .isEmpty() && open
                            .peek().level >= level) {
                open
                        .pop();
            }

            SectionDraft parent = open
                    .peek();
            String parentKey = parent == null ? "" : parent.sectionId;
            int sibling = siblings
                    .merge(
                            parentKey,
                            1,
                            Integer::sum
                    ) - 1;
            List<Integer> ordinalPath = new ArrayList<>(parent == null ? List
                    .of() : parent.ordinalPath);
            ordinalPath
                    .add(sibling);
            List<String> headingPath = new ArrayList<>(parent == null ? List
                    .of() : parent.headingPath);
            headingPath
                    .add(text);
            String elementId = heading
                    .id();
            String locator = elementId
                    .isEmpty() ? IdHash
                            .headingLocator(ordinalPath) : IdHash
                                    .headingLocator(
                                            elementId,
                                            occurrence(
                                                    headingIds,
                                                    elementId
                                            )
                                    );
            SectionDraft section = new SectionDraft(
                    IdHash
                            .sectionId(
                                    documentId,
                                    locator
                            ),
                    parent == null ? null : parent.sectionId,
                    locator,
                    elementId
                            .isEmpty() ? null : elementId,
                    List
                            .copyOf(headingPath),
                    level,
                    sections
                            .size(),
                    List
                            .copyOf(ordinalPath)
            );

            sections
                    .add(section);
            open
                    .push(section);
            current = section;
        }

        // 문단 블록, admonition 안이면 admonition 문단으로 모음
        private void paragraph(
                Element element,
                Sink sink
        ) {

            if (sink
                    .collectsAdmonition()) {
                pendingOf(sink)
                        .requestBreak();
                pendingOf(sink)
                        .appendChildren(element);

                return;
            }

            TextBuilder builder = new TextBuilder(identitySet())
                    .appendChildren(element);

            if (!builder
                    .text()
                    .isEmpty()) {
                emit(
                        sink,
                        BlockKind.PARAGRAPH,
                        builder
                                .text(),
                        null,
                        null,
                        null,
                        null,
                        builder,
                        element
                );
            }
        }

        // 블록 사이 인라인 노드를 문단 하나로
        private void flushRun(
                List<Node> run,
                Element container,
                Sink sink
        ) {

            if (run
                    .isEmpty()) {
                return;
            }

            if (sink
                    .collectsAdmonition()) {
                pendingOf(sink)
                        .appendNodes(run);
                run
                        .clear();

                return;
            }

            TextBuilder builder = new TextBuilder(identitySet())
                    .appendNodes(run);
            run
                    .clear();

            if (builder
                    .text()
                    .isEmpty()) {
                return;
            }

            emitAt(
                    sink,
                    BlockKind.PARAGRAPH,
                    builder
                            .text(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    builder,
                    domPath(container),
                    null
            );
        }

        // 코드 블록, 공백 보존, 코드 접기 대체 문구와 callout 표시 제거
        private void code(
                Element element,
                Sink sink
        ) {

            Element pre = element
                    .selectFirst("pre");

            if (pre == null) {
                visit(
                        element,
                        sink
                );

                return;
            }

            Element copy = pre
                    .clone();
            copy
                    .select("span.fold-block.is-hidden-unfolded")
                    .remove();

            for (Element conum : copy
                    .select("i.conum")) {
                Element next = conum
                        .nextElementSibling();

                if (next != null && next
                        .normalName()
                        .equals("b")) {
                    next
                            .remove();
                }

                conum
                        .remove();
            }

            String text = TextNormalizer
                    .normalize(copy
                            .wholeText());
            requireCodeSize(
                    text,
                    element
            );
            Element language = pre
                    .selectFirst("code[data-lang]");
            Element title = element
                    .selectFirst("> div.title");

            emit(
                    sink,
                    BlockKind.CODE,
                    text,
                    language == null ? null : language
                            .attr("data-lang"),
                    title == null ? null : new TextBuilder(identitySet())
                            .appendChildren(title)
                            .text(),
                    null,
                    null,
                    null,
                    element
            );
        }

        // 시 블록, 공백을 보존한 문단
        private void verse(
                Element element,
                Sink sink
        ) {

            if (sink
                    .collectsAdmonition()) {
                pendingOf(sink)
                        .requestBreak();
                pendingOf(sink)
                        .appendChildren(element);

                return;
            }

            Element pre = element
                    .selectFirst("pre");
            String text = TextNormalizer
                    .normalize(pre == null ? element
                            .wholeText() : pre
                                    .wholeText());

            if (!text
                    .isBlank()) {
                emit(
                        sink,
                        BlockKind.PARAGRAPH,
                        text,
                        null,
                        null,
                        null,
                        null,
                        null,
                        element
                );
            }
        }

        // 그림 블록, 제목이 있을 때만 문단
        private void image(
                Element element,
                Sink sink
        ) {

            Element title = element
                    .selectFirst("> div.title");

            if (title == null) {
                return;
            }

            if (sink
                    .collectsAdmonition()) {
                pendingOf(sink)
                        .requestBreak();
                pendingOf(sink)
                        .appendChildren(title);

                return;
            }

            TextBuilder builder = new TextBuilder(identitySet())
                    .appendChildren(title);

            if (!builder
                    .text()
                    .isEmpty()) {
                emit(
                        sink,
                        BlockKind.PARAGRAPH,
                        builder
                                .text(),
                        null,
                        null,
                        null,
                        null,
                        builder,
                        element
                );
            }
        }

        // admonition, 안의 코드와 탭 같은 블록은 펼쳐서 차례로 내보냄
        private void admonition(
                Element element,
                Sink sink
        ) {

            if (sink
                    .collectsAdmonition()) {
                flushAdmonition(sink);
            }

            String type = element
                    .classNames()
                    .stream()
                    .filter(name -> !name
                            .equals("admonitionblock"))
                    .findFirst()
                    .orElse("note");
            Element content = element
                    .selectFirst("td.content");
            Element body = content == null ? element : content;
            Element title = body
                    .selectFirst("> div.title");
            Sink inner = sink
                    .admonition(
                            type,
                            element,
                            title
                    );

            if (title != null) {
                pendingOf(inner)
                        .appendChildren(title);
                pendingOf(inner)
                        .requestBreak();
            }

            visit(
                    body,
                    inner
            );
            flushAdmonition(inner);
        }

        // 표, 셀 안에 블록이 있으면 보류
        private void table(
                Element element,
                Sink sink
        ) {

            List<Element> rows = new ArrayList<>();
            rows
                    .addAll(element
                            .select("> thead > tr"));
            int headRows = rows
                    .size();
            rows
                    .addAll(element
                            .select("> tbody > tr"));
            rows
                    .addAll(element
                            .select("> tfoot > tr"));
            List<TableRow> tableRows = new ArrayList<>();
            int rowCount = rows
                    .size();

            for (int index = 0; index < rowCount; index++) {
                Element row = rows
                        .get(index);
                List<Element> cells = row
                        .children()
                        .stream()
                        .filter(cell -> cell
                                .normalName()
                                .equals("td") || cell
                                        .normalName()
                                        .equals("th"))
                        .toList();
                boolean allHeaderCells = !cells
                        .isEmpty() && cells
                                .stream()
                                .allMatch(cell -> cell
                                        .normalName()
                                        .equals("th"));
                boolean header = index < headRows || headRows == 0 && index == 0 && allHeaderCells;
                List<TableCell> tableCells = new ArrayList<>();

                for (Element cell : cells) {
                    if (!cell
                            .select(NESTED_BLOCKS)
                            .isEmpty()) {
                        throw new DocumentHoldException(
                                HoldReason.BLOCK_NESTING_UNSUPPORTED,
                                "block inside table cell at " + domPath(cell)
                        );
                    }

                    TextBuilder builder = new TextBuilder(identitySet())
                            .appendChildren(cell);
                    tableCells
                            .add(new TableCell(
                                    builder
                                            .text(),
                                    span(
                                            cell,
                                            "rowspan"
                                    ),
                                    span(
                                            cell,
                                            "colspan"
                                    ),
                                    builder
                                            .inlineCodes(),
                                    builder
                                            .links(),
                                    domPath(cell)
                            ));
                }

                tableRows
                        .add(new TableRow(
                                header,
                                tableCells,
                                domPath(row)
                        ));
            }

            Element caption = element
                    .selectFirst("> caption");

            emit(
                    sink,
                    BlockKind.TABLE,
                    null,
                    null,
                    null,
                    new TableData(
                            caption == null ? null : new TextBuilder(identitySet())
                                    .appendChildren(caption)
                                    .text(),
                            tableRows
                    ),
                    null,
                    null,
                    element
            );
        }

        // 목록, 항목마다 표시 기호와 텍스트 한 줄, 중첩 목록은 들여쓰기
        private void list(
                Element element,
                Sink sink
        ) {

            if (!element
                    .select(NESTED_BLOCKS)
                    .isEmpty()) {
                throw new DocumentHoldException(
                        HoldReason.BLOCK_NESTING_UNSUPPORTED,
                        "block inside list at " + domPath(element)
                );
            }

            ListWriter writer = new ListWriter();
            writer
                    .writeList(
                            element,
                            0
                    );

            String text = writer.text
                    .text();

            if (!text
                    .isEmpty()) {
                emit(
                        sink,
                        BlockKind.LIST,
                        text,
                        null,
                        null,
                        null,
                        null,
                        writer,
                        element
                );
            }
        }

        // 탭 그룹과 모든 변형, 기본 변형은 서비스가 정책으로 결정
        private void tabs(
                Element element,
                Sink sink
        ) {

            boolean nested = element
                    .getElementsByClass("tabs")
                    .stream()
                    .anyMatch(group -> group != element && isTabGroup(group));

            if (sink.panel || nested) {
                throw new DocumentHoldException(
                        HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                        "nested tab group at " + domPath(element)
                );
            }

            requireTabChildren(element);

            List<Element> tabs = element
                    .select(profile
                            .tabSelectors()
                            .tab());
            List<Element> panels = element
                    .select("div.tabpanel");

            if (tabs
                    .isEmpty()) {
                throw new DocumentHoldException(
                        HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                        "tab group without tabs at " + domPath(element)
                );
            }

            Set<Element> usedPanels = identitySet();
            Map<String, Integer> labelCounts = new HashMap<>();
            List<TabVariant> variants = new ArrayList<>();
            List<Integer> marked = new ArrayList<>();
            List<String> canonicalLabels = new ArrayList<>();
            boolean allMapped = true;
            int tabCount = tabs
                    .size();

            for (int ordinal = 0; ordinal < tabCount; ordinal++) {
                Element tab = tabs
                        .get(ordinal);
                Element panel = panelOf(
                        tab,
                        panels
                );
                usedPanels
                        .add(panel);
                String label = new TextBuilder(identitySet())
                        .appendChildren(tab)
                        .text();
                boolean mapped = profile
                        .canonicalLabels()
                        .containsKey(label);
                String canonical = mapped ? profile
                        .canonicalLabels()
                        .get(label) : TextNormalizer
                                .normalize(label);
                List<Block> blocks = new ArrayList<>();

                visit(
                        panel,
                        Sink
                                .panel(blocks)
                );
                requireVariantSize(
                        blocks,
                        panel
                );

                if (tab
                        .hasClass("is-selected") || "true"
                                .equals(tab
                                        .attr("aria-selected"))) {
                    marked
                            .add(ordinal);
                }

                allMapped &= mapped;
                canonicalLabels
                        .add(canonical);
                variants
                        .add(new TabVariant(
                                label,
                                canonical,
                                IdHash
                                        .variantKey(
                                                canonical,
                                                occurrence(
                                                        labelCounts,
                                                        canonical
                                                ),
                                                mapped
                                        ),
                                ordinal,
                                blocks,
                                pointer(
                                        domPath(panel),
                                        panel
                                                .id(),
                                        ordinal
                                )
                        ));
            }

            if (usedPanels
                    .size() != panels
                            .size()) {
                throw new DocumentHoldException(
                        HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                        "panel without tab at " + domPath(element)
                );
            }

            String elementId = element
                    .id();
            int groupOrdinal = tabGroups++;
            String locator = elementId
                    .isEmpty() ? IdHash
                            .tabGroupLocator(groupOrdinal) : IdHash
                                    .tabGroupLocator(
                                            elementId,
                                            occurrence(
                                                    tabIds,
                                                    elementId
                                            )
                                    );

            Element title = element
                    .selectFirst("> div.title");

            emit(
                    sink,
                    BlockKind.TAB_GROUP,
                    null,
                    null,
                    title == null ? null : new TextBuilder(identitySet())
                            .appendChildren(title)
                            .text(),
                    null,
                    new TabGroup(
                            locator,
                            elementId
                                    .isEmpty() ? null : elementId,
                            groupType(
                                    canonicalLabels,
                                    allMapped
                            ),
                            marked,
                            null,
                            null,
                            variants
                    ),
                    null,
                    element
            );
        }

        // 탭 그룹 안에는 제목, 탭 목록, 패널만 허용, 다른 내용은 버리지 않고 보류
        private void requireTabChildren(Element group) {

            for (Element child : group
                    .children()) {
                boolean known = child
                        .hasClass("title") || child
                                .hasClass("content");

                if (!known) {
                    throw new DocumentHoldException(
                            HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                            "unexpected element in tab group at " + domPath(child)
                    );
                }

                if (!child
                        .hasClass("content")) {
                    continue;
                }

                for (Element part : child
                        .children()) {
                    if (!part
                            .hasClass("tablist") && !part
                                    .hasClass("tabpanel")) {
                        throw new DocumentHoldException(
                                HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                                "unexpected element in tab group at " + domPath(part)
                        );
                    }
                }
            }
        }

        // 탭 id가 aria-labelledby에 든 패널 하나, 없거나 여럿이면 보류
        private Element panelOf(
                Element tab,
                List<Element> panels
        ) {

            String tabId = tab
                    .id();
            List<Element> matches = panels
                    .stream()
                    .filter(panel -> !tabId
                            .isEmpty() && List
                                    .of(panel
                                            .attr("aria-labelledby")
                                            .trim()
                                            .split("\\s+"))
                                    .contains(tabId))
                    .toList();

            if (matches
                    .size() != 1) {
                throw new DocumentHoldException(
                        HoldReason.TAB_STRUCTURE_UNSUPPORTED,
                        matches
                                .size() + " panels for tab " + tabId
                );
            }

            return matches
                    .getFirst();
        }

        // 라벨이 모두 대응표를 거쳐 한 유형 집합에 들면 그 유형, 아니면 other
        private VariantGroupType groupType(
                List<String> canonicalLabels,
                boolean allMapped
        ) {

            if (!allMapped) {
                return VariantGroupType.OTHER;
            }

            for (VariantGroupType type : VariantGroupType
                    .values()) {
                Set<String> members = profile
                        .groupTypes()
                        .get(type);

                if (members != null && members
                        .containsAll(canonicalLabels)) {
                    return type;
                }
            }

            return VariantGroupType.OTHER;
        }

        // 모은 admonition 문단을 블록 하나로 내보냄
        private void flushAdmonition(Sink sink) {

            TextBuilder builder = sink.pending;
            sink.pending = null;

            if (builder == null || builder
                    .text()
                    .isEmpty()) {
                return;
            }

            emitAt(
                    sink,
                    BlockKind.ADMONITION,
                    builder
                            .text(),
                    null,
                    null,
                    sink.admonitionType,
                    null,
                    null,
                    builder,
                    domPath(sink.admonition),
                    blankToNull(sink.admonition
                            .id())
            );
        }

        // admonition 문단을 모으는 텍스트
        private TextBuilder pendingOf(Sink sink) {

            if (sink.pending == null) {
                sink.pending = new TextBuilder(identitySet());
            }

            return sink.pending;
        }

        // 요소 위치로 블록 내보내기
        private void emit(
                Sink sink,
                BlockKind kind,
                String text,
                String language,
                String title,
                TableData table,
                TabGroup tabGroup,
                Positions positions,
                Element element
        ) {

            emitAt(
                    sink,
                    kind,
                    text,
                    language,
                    title,
                    null,
                    table,
                    tabGroup,
                    positions,
                    domPath(element),
                    blankToNull(element
                            .id())
            );
        }

        // 블록을 섹션이나 패널 목록에 추가, admonition 문단을 먼저 내보냄
        private void emitAt(
                Sink sink,
                BlockKind kind,
                String text,
                String language,
                String title,
                String admonitionType,
                TableData table,
                TabGroup tabGroup,
                Positions positions,
                String domPath,
                String elementId
        ) {

            if (kind != BlockKind.ADMONITION && sink
                    .collectsAdmonition()) {
                flushAdmonition(sink);
            }

            if (current == null) {
                throw new DocumentHoldException(
                        HoldReason.TITLE_MISSING,
                        "content before the first heading at " + domPath
                );
            }

            if (text != null && !Normalizer
                    .isNormalized(
                            text,
                            Normalizer.Form.NFC
                    )) {
                throw new DocumentHoldException(
                        HoldReason.INVALID_UNICODE,
                        "combining character split across inline elements at " + domPath
                );
            }

            List<Block> target = sink.blocks == null ? current.blocks : sink.blocks;
            int ordinal = target
                    .size();

            target
                    .add(new Block(
                            sink.blocks == null ? IdHash
                                    .blockId(
                                            current.sectionId,
                                            ordinal
                                    ) : null,
                            kind,
                            ordinal,
                            text,
                            language,
                            title,
                            admonitionType,
                            table,
                            tabGroup,
                            positions == null ? List
                                    .of() : positions
                                            .inlineCodes(),
                            positions == null ? List
                                    .of() : positions
                                            .links(),
                            pointer(
                                    domPath,
                                    elementId,
                                    ordinal
                            )
                    ));
        }

        // 현재 섹션 기준 원본 위치
        private SourcePointer pointer(
                String domPath,
                String elementId,
                int ordinal
        ) {

            return new SourcePointer(
                    domPath,
                    blankToNull(elementId),
                    current == null ? null : current.headingLocator,
                    ordinal
            );
        }

        // 코드가 1 MiB를 넘으면 보류
        private void requireCodeSize(
                String code,
                Element element
        ) {

            if (TextNormalizer
                    .utf8Length(code) > MAX_CODE_BYTES) {
                throw new DocumentHoldException(
                        HoldReason.CODE_OVER_HARD_LIMIT,
                        "code over 1 MiB at " + domPath(element)
                );
            }
        }

        // 탭 변형 내용 합이 1 MiB를 넘으면 보류
        private void requireVariantSize(
                List<Block> blocks,
                Element panel
        ) {

            long bytes = 0;

            for (Block block : blocks) {
                if (block
                        .text() != null) {
                    bytes += TextNormalizer
                            .utf8Length(block
                                    .text());
                }

                if (block
                        .table() != null) {
                    for (TableRow row : block
                            .table()
                            .rows()) {
                        for (TableCell cell : row
                                .cells()) {
                            bytes += TextNormalizer
                                    .utf8Length(cell
                                            .text());
                        }
                    }
                }
            }

            if (bytes > MAX_CODE_BYTES) {
                throw new DocumentHoldException(
                        HoldReason.CODE_OVER_HARD_LIMIT,
                        "tab variant over 1 MiB at " + domPath(panel)
                );
            }
        }

        // 본문 root부터 요소 자식 순번을 / 로 이은 경로
        private String domPath(Element element) {

            List<String> parts = new ArrayList<>();
            Element cursor = element;

            while (cursor != null && cursor != root) {
                parts
                        .add(Integer
                                .toString(cursor
                                        .elementSiblingIndex()));
                cursor = cursor
                        .parent();
            }

            Collections
                    .reverse(parts);

            return String
                    .join(
                            "/",
                            parts
                    );
        }

        // 같은 NFC 값의 몇 번째 등장인지
        private static int occurrence(
                Map<String, Integer> counts,
                String value
        ) {

            return counts
                    .merge(
                            TextNormalizer
                                    .normalize(value),
                            1,
                            Integer::sum
                    ) - 1;
        }

        // rowspan, colspan 값, 없거나 잘못되면 1
        private static int span(
                Element cell,
                String attribute
        ) {

            try {
                return Math
                        .max(
                                1,
                                Integer
                                        .parseInt(cell
                                                .attr(attribute)
                                                .trim())
                        );
            } catch (NumberFormatException exception) {
                return 1;
            }
        }

        // ol start 속성, 없거나 잘못되면 1
        private static int start(Element list) {

            try {
                return Integer
                        .parseInt(list
                                .attr("start")
                                .trim());
            } catch (NumberFormatException exception) {
                return 1;
            }
        }

        // 빈 문자열은 null
        private static String blankToNull(String value) {

            return value == null || value
                    .isEmpty() ? null : value;
        }

        // href를 finalUrl 기준으로 해석, http(s)만 targetUrl
        private BlockLink resolveLink(
                String label,
                String href,
                int start
        ) {

            String targetUrl = null;
            String targetAnchor = "";

            try {
                if (base != null) {
                    URI resolved = base
                            .resolve(new URI(href));
                    String scheme = resolved
                            .getScheme();

                    if ("http"
                            .equalsIgnoreCase(scheme) || "https"
                                    .equalsIgnoreCase(scheme)) {
                        String full = resolved
                                .toString();
                        int hash = full
                                .indexOf('#');
                        targetUrl = hash < 0 ? full : full
                                .substring(
                                        0,
                                        hash
                                );
                        targetAnchor = resolved
                                .getFragment() == null ? "" : resolved
                                        .getFragment();
                    }
                }
            } catch (URISyntaxException | IllegalArgumentException exception) {
                targetUrl = null;
                targetAnchor = "";
            }

            return new BlockLink(
                    label,
                    href,
                    targetUrl,
                    targetAnchor,
                    start
            );
        }

        // 인라인 코드와 링크 위치를 가진 텍스트
        private interface Positions {

            /** 인라인 코드 목록 */
            List<InlineCode> inlineCodes();

            /** 링크 목록 */
            List<BlockLink> links();
        }

        // 화면 표시처럼 공백을 접은 텍스트, 블록 경계와 br은 줄바꿈, 위치는 code point
        private final class TextBuilder implements Positions {

            private final Set<Element> skip;
            private final StringBuilder text = new StringBuilder();
            private final List<InlineCode> inlineCodes = new ArrayList<>();
            private final List<BlockLink> links = new ArrayList<>();
            private int pending = NONE;
            private int codePoints;

            // 이 텍스트에서만 건너뛸 요소
            private TextBuilder(Set<Element> skip) {

                this.skip = skip;
            }

            // 노드 목록 추가
            TextBuilder appendNodes(List<? extends Node> nodes) {

                nodes
                        .forEach(this::appendNode);

                return this;
            }

            // 요소의 자식 노드 추가
            TextBuilder appendChildren(Element element) {

                return appendNodes(element
                        .childNodes());
            }

            // 다음 내용 앞에 줄바꿈 예약
            void requestBreak() {

                if (codePoints > 0) {
                    pending = BREAK;
                }
            }

            // 접은 텍스트
            String text() {

                return text
                        .toString();
            }

            /** 인라인 코드 목록 */
            @Override
            public List<InlineCode> inlineCodes() {

                return List
                        .copyOf(inlineCodes);
            }

            /** 링크 목록 */
            @Override
            public List<BlockLink> links() {

                return List
                        .copyOf(links);
            }

            // 다른 텍스트를 새 줄에서 위치와 함께 이어 붙임
            void appendBuilt(
                    String prefix,
                    TextBuilder other
            ) {

                if (codePoints > 0) {
                    text
                            .append('\n');
                    codePoints++;
                }

                appendBuiltInline(
                        prefix,
                        other
                );
            }

            // 다른 텍스트를 같은 줄에 위치와 함께 이어 붙임
            void appendBuiltInline(
                    String prefix,
                    TextBuilder other
            ) {

                pending = NONE;
                text
                        .append(prefix);
                codePoints += prefix
                        .codePointCount(
                                0,
                                prefix
                                        .length()
                        );
                int base = codePoints;

                other.inlineCodes
                        .forEach(code -> inlineCodes
                                .add(new InlineCode(
                                        code
                                                .text(),
                                        code
                                                .start() + base
                                )));
                other.links
                        .forEach(link -> links
                                .add(new BlockLink(
                                        link
                                                .label(),
                                        link
                                                .href(),
                                        link
                                                .targetUrl(),
                                        link
                                                .targetAnchor(),
                                        link
                                                .start() + base
                                )));
                text
                        .append(other.text);
                codePoints += other.codePoints;
            }

            // 노드 종류별 추가
            private void appendNode(Node node) {

                if (node instanceof TextNode textNode) {
                    appendText(textNode
                            .getWholeText());

                    return;
                }

                if (!(node instanceof Element element) || excluded
                        .contains(element) || skip
                                .contains(element) || SILENT_TAGS
                                        .contains(element
                                                .normalName())) {
                    return;
                }

                String tag = element
                        .normalName();

                if (tag
                        .equals("br")) {
                    requestBreak();

                    return;
                }

                if (tag
                        .equals("code")) {
                    appendCode(element);

                    return;
                }

                if (tag
                        .equals("a") && element
                                .hasAttr("href")) {
                    appendLink(element);

                    return;
                }

                if (element
                        .tag()
                        .isBlock()) {
                    requestBreak();
                    appendChildren(element);
                    requestBreak();

                    return;
                }

                appendChildren(element);
            }

            // 인라인 코드 텍스트와 위치
            private void appendCode(Element element) {

                flushPending();
                int start = codePoints;
                int startChar = text
                        .length();
                appendChildren(element);
                String code = text
                        .substring(startChar);

                if (!code
                        .isEmpty()) {
                    inlineCodes
                            .add(new InlineCode(
                                    code,
                                    start
                            ));
                }
            }

            // 링크 텍스트와 위치
            private void appendLink(Element element) {

                flushPending();
                int start = codePoints;
                int startChar = text
                        .length();
                appendChildren(element);
                links
                        .add(resolveLink(
                                text
                                        .substring(startChar),
                                element
                                        .attr("href"),
                                start
                        ));
            }

            // 공백을 접으며 텍스트 추가
            private void appendText(String raw) {

                String normalized = TextNormalizer
                        .normalize(raw);
                int length = normalized
                        .length();
                int index = 0;

                while (index < length) {
                    int codePoint = normalized
                            .codePointAt(index);
                    index += Character
                            .charCount(codePoint);

                    if (Character
                            .isWhitespace(codePoint) || codePoint == 0xa0) {
                        if (codePoints > 0 && pending == NONE) {
                            pending = SPACE;
                        }

                        continue;
                    }

                    flushPending();
                    text
                            .appendCodePoint(codePoint);
                    codePoints++;
                }
            }

            // 예약한 공백이나 줄바꿈 쓰기
            private void flushPending() {

                if (codePoints > 0 && pending == SPACE) {
                    text
                            .append(' ');
                    codePoints++;
                } else if (codePoints > 0 && pending == BREAK) {
                    text
                            .append('\n');
                    codePoints++;
                }

                pending = NONE;
            }
        }

        // 목록 블록을 줄 단위 텍스트로
        private final class ListWriter implements Positions {

            private final TextBuilder text = new TextBuilder(identitySet());

            /** 인라인 코드 목록 */
            @Override
            public List<InlineCode> inlineCodes() {

                return text
                        .inlineCodes();
            }

            /** 링크 목록 */
            @Override
            public List<BlockLink> links() {

                return text
                        .links();
            }

            // 목록 종류별 항목 쓰기
            void writeList(
                    Element list,
                    int depth
            ) {

                if (list
                        .hasClass("dlist")) {
                    writeDefinitions(
                            list,
                            depth
                    );
                } else if (list
                        .hasClass("hdlist")) {
                    writeHorizontal(
                            list,
                            depth
                    );
                } else if (list
                        .hasClass("colist") && list
                                .selectFirst("> table") != null) {
                    writeCallouts(
                            list,
                            depth
                    );
                } else {
                    writeItems(
                            list,
                            depth
                    );
                }
            }

            // ul, ol 항목, ol은 실제 번호
            private void writeItems(
                    Element list,
                    int depth
            ) {

                Element items = list
                        .selectFirst("> ul, > ol");

                if (items == null) {
                    return;
                }

                boolean ordered = items
                        .normalName()
                        .equals("ol");
                int number = ordered ? start(items) : 0;

                for (Element item : items
                        .children()) {
                    if (!item
                            .normalName()
                            .equals("li")) {
                        continue;
                    }

                    writeLine(
                            depth,
                            ordered ? number++ + "." : "-",
                            item,
                            null
                    );
                }
            }

            // 정의 목록, 용어와 설명을 한 줄로
            private void writeDefinitions(
                    Element list,
                    int depth
            ) {

                Element definitions = list
                        .selectFirst("> dl");

                if (definitions == null) {
                    return;
                }

                for (Element term : definitions
                        .select("> dt")) {
                    Element description = term
                            .nextElementSibling();

                    writeLine(
                            depth,
                            "-",
                            term,
                            description != null && description
                                    .normalName()
                                    .equals("dd") ? description : null
                    );
                }
            }

            // 가로 정의 목록, 용어 셀과 설명 셀
            private void writeHorizontal(
                    Element list,
                    int depth
            ) {

                for (Element row : list
                        .select("> table > tbody > tr")) {
                    Element term = row
                            .selectFirst("td.hdlist1");
                    Element description = row
                            .selectFirst("td.hdlist2");

                    if (term != null) {
                        writeLine(
                                depth,
                                "-",
                                term,
                                description
                        );
                    }
                }
            }

            // callout 설명 목록, 번호와 설명
            private void writeCallouts(
                    Element list,
                    int depth
            ) {

                for (Element row : list
                        .select("> table tr")) {
                    List<Element> cells = row
                            .select("> td");

                    if (cells
                            .size() < 2) {
                        continue;
                    }

                    String number = new TextBuilder(identitySet())
                            .appendChildren(cells
                                    .getFirst())
                            .text();

                    writeLine(
                            depth,
                            number + ".",
                            cells
                                    .get(1),
                            null
                    );
                }
            }

            // 한 항목 줄, 항목 안 중첩 목록은 다음 깊이로
            private void writeLine(
                    int depth,
                    String marker,
                    Element item,
                    Element description
            ) {

                List<Element> candidates = new ArrayList<>(item
                        .select(LIST_BLOCKS));

                if (description != null) {
                    candidates
                            .addAll(description
                                    .select(LIST_BLOCKS));
                }

                List<Element> children = candidates
                        .stream()
                        .filter(element -> element != item && element
                                .parents()
                                .stream()
                                .noneMatch(candidates::contains))
                        .toList();
                Set<Element> nested = identitySet();
                nested
                        .addAll(children);
                TextBuilder line = new TextBuilder(nested)
                        .appendChildren(item);

                if (description != null) {
                    TextBuilder detail = new TextBuilder(nested)
                            .appendChildren(description);

                    if (!detail
                            .text()
                            .isEmpty()) {
                        line
                                .appendBuiltInline(
                                        ": ",
                                        detail
                                );
                    }
                }

                text
                        .appendBuilt(
                                "  "
                                        .repeat(depth) + marker + " ",
                                line
                        );

                for (Element child : children) {
                    writeList(
                            child,
                            depth + 1
                    );
                }
            }
        }
    }
}
