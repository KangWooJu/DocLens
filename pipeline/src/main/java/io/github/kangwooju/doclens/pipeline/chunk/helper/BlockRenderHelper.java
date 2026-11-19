package io.github.kangwooju.doclens.pipeline.chunk.helper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.chunk.dto.RenderUnit;
import io.github.kangwooju.doclens.pipeline.chunk.dto.RenderedBlock;
import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabVariant;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableCell;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableRow;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;

/**
 * 블록을 청크 content 텍스트로 렌더링
 * 코드는 fenced block, 표는 Markdown, admonition은 종류 접두, 탭은 기본 변형만
 */
@Component
public class BlockRenderHelper {

    /** 블록 사이 구분자 */
    public static final String SEPARATOR = "\n\n";

    /** 블록 하나의 렌더링 결과 */
    public RenderedBlock render(Block block) {

        return switch (block
                .kind()) {
            case PARAGRAPH -> plain(
                    block,
                    "",
                    List
                            .of()
            );
            case ADMONITION -> plain(
                    block,
                    block
                            .admonitionType()
                            .toUpperCase(Locale.ROOT) + ": ",
                    List
                            .of()
            );
            case LIST -> plain(
                    block,
                    "",
                    lineUnits(
                            block
                                    .text(),
                            block
                                    .inlineCodes(),
                            block
                                    .links()
                    )
            );
            case CODE -> code(block);
            case TABLE -> table(block);
            case TAB_GROUP -> tabs(block);
        };
    }

    /** 탭 변형의 저장 내용, CODE 하나뿐이면 원문 코드, 아니면 렌더링 텍스트 */
    public String variantContent(TabVariant variant) {

        List<Block> blocks = variant
                .blocks();

        if (blocks
                .size() == 1 && blocks
                        .getFirst()
                        .kind() == BlockKind.CODE) {
            return blocks
                    .getFirst()
                    .text();
        }

        return joined(blocks)
                .text();
    }

    // 접두를 붙인 텍스트 블록
    private static RenderedBlock plain(
            Block block,
            String prefix,
            List<RenderUnit> units
    ) {

        int shift = codePoints(prefix);
        String text = prefix + block
                .text();

        return new RenderedBlock(
                block,
                text,
                TextNormalizer
                        .utf8Length(text),
                null,
                0,
                units,
                shiftCodes(
                        block
                                .inlineCodes(),
                        shift
                ),
                shiftLinks(
                        block
                                .links(),
                        shift
                )
        );
    }

    // 제목 줄과 fenced 코드, fence는 코드 안 가장 긴 백틱보다 길게
    private static RenderedBlock code(Block block) {

        String code = block
                .text();
        String fence = "`"
                .repeat(Math
                        .max(
                                3,
                                longestBacktickRun(code) + 1
                        ));
        StringBuilder text = new StringBuilder();

        if (block
                .title() != null) {
            text
                    .append(block
                            .title())
                    .append('\n');
        }

        text
                .append(fence)
                .append(block
                        .language() == null ? "" : block
                                .language())
                .append('\n')
                .append(code);

        if (!code
                .endsWith("\n")) {
            text
                    .append('\n');
        }

        text
                .append(fence);

        String rendered = text
                .toString();

        return new RenderedBlock(
                block,
                rendered,
                TextNormalizer
                        .utf8Length(rendered),
                null,
                0,
                List
                        .of(),
                List
                        .of(),
                List
                        .of()
        );
    }

    // 제목 줄과 기본 변형 블록만 렌더링한 탭 그룹
    private RenderedBlock tabs(Block block) {

        TabGroup group = block
                .tabGroup();
        TabVariant defaultVariant = group
                .variants()
                .stream()
                .filter(variant -> variant
                        .variantKey()
                        .equals(group
                                .defaultVariant()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("tab group without default variant " + group
                        .groupLocator()));
        RenderedBlock joined = joined(defaultVariant
                .blocks());
        String prefix = block
                .title() == null ? "" : block
                        .title() + "\n";
        int shift = codePoints(prefix);
        String text = prefix + joined
                .text();

        return new RenderedBlock(
                block,
                text,
                TextNormalizer
                        .utf8Length(text),
                null,
                0,
                List
                        .of(),
                shiftCodes(
                        joined
                                .inlineCodes(),
                        shift
                ),
                shiftLinks(
                        joined
                                .links(),
                        shift
                )
        );
    }

    // 블록 목록을 구분자로 이은 렌더링
    private RenderedBlock joined(List<Block> blocks) {

        StringBuilder text = new StringBuilder();
        List<InlineCode> codes = new ArrayList<>();
        List<BlockLink> links = new ArrayList<>();

        for (Block block : blocks) {
            if (!text
                    .isEmpty()) {
                text
                        .append(SEPARATOR);
            }

            int shift = codePoints(text);
            RenderedBlock rendered = render(block);
            codes
                    .addAll(shiftCodes(
                            rendered
                                    .inlineCodes(),
                            shift
                    ));
            links
                    .addAll(shiftLinks(
                            rendered
                                    .links(),
                            shift
                    ));
            text
                    .append(rendered
                            .text());
        }

        String result = text
                .toString();

        return new RenderedBlock(
                null,
                result,
                TextNormalizer
                        .utf8Length(result),
                null,
                0,
                List
                        .of(),
                codes,
                links
        );
    }

    // caption, header, 구분 줄과 본문 행 묶음으로 만든 Markdown 표
    private static RenderedBlock table(Block block) {

        List<TableRow> rows = block
                .table()
                .rows();
        List<List<String>> grid = expand(rows);
        int width = grid
                .stream()
                .mapToInt(List::size)
                .max()
                .orElse(0);
        List<String> headerLines = new ArrayList<>();
        List<InlineCode> codes = new ArrayList<>();
        List<BlockLink> links = new ArrayList<>();

        if (block
                .table()
                .caption() != null) {
            headerLines
                    .add(block
                            .table()
                            .caption());
        }

        int bodyStart = 0;
        int rowCount = rows
                .size();

        while (bodyStart < rowCount && rows
                .get(bodyStart)
                .header()) {
            headerLines
                    .add(line(
                            grid
                                    .get(bodyStart),
                            width
                    ));
            collect(
                    rows
                            .get(bodyStart),
                    0,
                    codes,
                    links
            );
            bodyStart++;
        }

        if (bodyStart > 0) {
            headerLines
                    .add(line(
                            List
                                    .of(),
                            width,
                            "---"
                    ));
        }

        String header = String
                .join(
                        "\n",
                        headerLines
                );
        StringBuilder text = new StringBuilder(header);
        List<RenderUnit> units = new ArrayList<>();
        int bodyRows = rowCount - bodyStart;
        int start = 0;

        while (start < bodyRows) {
            int end = groupEnd(
                    rows,
                    bodyStart,
                    start
            );
            List<String> lines = new ArrayList<>();
            List<InlineCode> unitCodes = new ArrayList<>();
            List<BlockLink> unitLinks = new ArrayList<>();

            if (!text
                    .isEmpty()) {
                text
                        .append('\n');
            }

            int charStart = codePoints(text);

            for (int row = start; row < end; row++) {
                lines
                        .add(line(
                                grid
                                        .get(bodyStart + row),
                                width
                        ));
                collect(
                        rows
                                .get(bodyStart + row),
                        charStart,
                        unitCodes,
                        unitLinks
                );
            }

            String unitText = String
                    .join(
                            "\n",
                            lines
                    );
            text
                    .append(unitText);
            units
                    .add(new RenderUnit(
                            unitText,
                            charStart,
                            start,
                            end,
                            unitCodes,
                            unitLinks
                    ));
            codes
                    .addAll(unitCodes);
            links
                    .addAll(unitLinks);
            start = end;
        }

        String rendered = text
                .toString();

        return new RenderedBlock(
                block,
                rendered,
                TextNormalizer
                        .utf8Length(rendered),
                header,
                bodyRows,
                units,
                codes,
                links
        );
    }

    // rowspan과 colspan을 펼친 셀 값 격자, 병합 셀은 덮는 칸마다 같은 값
    private static List<List<String>> expand(List<TableRow> rows) {

        List<List<String>> grid = new ArrayList<>();
        Map<Integer, String> carriedText = new HashMap<>();
        TreeMap<Integer, Integer> carriedRows = new TreeMap<>();

        for (TableRow row : rows) {
            List<String> cells = new ArrayList<>();
            Iterator<TableCell> pending = row
                    .cells()
                    .iterator();
            int column = 0;

            while (pending
                    .hasNext() || carriedRows
                            .ceilingKey(column) != null) {
                Integer remaining = carriedRows
                        .get(column);

                if (remaining != null) {
                    cells
                            .add(carriedText
                                    .get(column));

                    if (remaining == 1) {
                        carriedRows
                                .remove(column);
                    } else {
                        carriedRows
                                .put(
                                        column,
                                        remaining - 1
                                );
                    }

                    column++;

                    continue;
                }

                if (!pending
                        .hasNext()) {
                    cells
                            .add("");
                    column++;

                    continue;
                }

                TableCell cell = pending
                        .next();
                String value = cell
                        .text()
                        .replace(
                                "|",
                                "\\|"
                        )
                        .replace(
                                '\n',
                                ' '
                        );

                for (int span = 0; span < cell
                        .colspan(); span++) {
                    cells
                            .add(value);

                    if (cell
                            .rowspan() > 1) {
                        carriedText
                                .put(
                                        column,
                                        value
                                );
                        carriedRows
                                .put(
                                        column,
                                        cell
                                                .rowspan() - 1
                                );
                    }

                    column++;
                }
            }

            grid
                    .add(cells);
        }

        return grid;
    }

    // 본문 행 start부터 rowspan으로 이어진 행 묶음의 끝
    private static int groupEnd(
            List<TableRow> rows,
            int bodyStart,
            int start
    ) {

        int end = start + 1;
        int row = start;

        while (row < end) {
            for (TableCell cell : rows
                    .get(bodyStart + row)
                    .cells()) {
                end = Math
                        .max(
                                end,
                                row + cell
                                        .rowspan()
                        );
            }

            row++;
        }

        return Math
                .min(
                        end,
                        rows
                                .size() - bodyStart
                );
    }

    // 행 셀의 인라인 코드와 링크를 위치 base로 모음
    private static void collect(
            TableRow row,
            int base,
            List<InlineCode> codes,
            List<BlockLink> links
    ) {

        for (TableCell cell : row
                .cells()) {
            codes
                    .addAll(shiftCodes(
                            cell
                                    .inlineCodes()
                                    .stream()
                                    .map(code -> new InlineCode(
                                            code
                                                    .text(),
                                            0
                                    ))
                                    .toList(),
                            base
                    ));
            links
                    .addAll(shiftLinks(
                            cell
                                    .links()
                                    .stream()
                                    .map(link -> new BlockLink(
                                            link
                                                    .label(),
                                            link
                                                    .href(),
                                            link
                                                    .targetUrl(),
                                            link
                                                    .targetAnchor(),
                                            0
                                    ))
                                    .toList(),
                            base
                    ));
        }
    }

    // Markdown 표 한 줄, 모자란 칸은 빈 값
    private static String line(
            List<String> cells,
            int width
    ) {

        return line(
                cells,
                width,
                ""
        );
    }

    // Markdown 표 한 줄, 모자란 칸은 filler
    private static String line(
            List<String> cells,
            int width,
            String filler
    ) {

        List<String> padded = new ArrayList<>(cells);

        while (padded
                .size() < width) {
            padded
                    .add(filler);
        }

        return "| " + String
                .join(
                        " | ",
                        padded
                ) + " |";
    }

    // 줄마다 나눈 단위와 그 줄에 시작점이 든 인라인 코드, 링크
    private static List<RenderUnit> lineUnits(
            String text,
            List<InlineCode> codes,
            List<BlockLink> links
    ) {

        List<RenderUnit> units = new ArrayList<>();
        int charStart = 0;

        for (String line : text
                .split(
                        "\n",
                        -1
                )) {
            int start = charStart;
            int end = start + codePoints(line);
            units
                    .add(new RenderUnit(
                            line,
                            start,
                            0,
                            0,
                            codes
                                    .stream()
                                    .filter(code -> code
                                            .start() >= start && code
                                                    .start() < end)
                                    .toList(),
                            links
                                    .stream()
                                    .filter(link -> link
                                            .start() >= start && link
                                                    .start() < end)
                                    .toList()
                    ));
            charStart = end + 1;
        }

        return units;
    }

    // 코드 안 가장 긴 백틱 연속 길이
    private static int longestBacktickRun(String code) {

        int longest = 0;
        int current = 0;
        int length = code
                .length();

        for (int index = 0; index < length; index++) {
            current = code
                    .charAt(index) == '`' ? current + 1 : 0;
            longest = Math
                    .max(
                            longest,
                            current
                    );
        }

        return longest;
    }

    // 인라인 코드 위치 이동
    private static List<InlineCode> shiftCodes(
            List<InlineCode> codes,
            int shift
    ) {

        return codes
                .stream()
                .map(code -> new InlineCode(
                        code
                                .text(),
                        code
                                .start() + shift
                ))
                .toList();
    }

    // 링크 위치 이동
    private static List<BlockLink> shiftLinks(
            List<BlockLink> links,
            int shift
    ) {

        return links
                .stream()
                .map(link -> new BlockLink(
                        link
                                .label(),
                        link
                                .href(),
                        link
                                .targetUrl(),
                        link
                                .targetAnchor(),
                        link
                                .start() + shift
                ))
                .toList();
    }

    // code point 수
    private static int codePoints(CharSequence text) {

        return Character
                .codePointCount(
                        text,
                        0,
                        text
                                .length()
                );
    }
}
