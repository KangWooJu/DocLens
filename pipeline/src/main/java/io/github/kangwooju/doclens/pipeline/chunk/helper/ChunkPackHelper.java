package io.github.kangwooju.doclens.pipeline.chunk.helper;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkDraft;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkMember;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkPiece;
import io.github.kangwooju.doclens.pipeline.chunk.dto.RenderUnit;
import io.github.kangwooju.doclens.pipeline.chunk.dto.RenderedBlock;
import io.github.kangwooju.doclens.pipeline.chunk.type.MemberRole;
import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;

/**
 * 한 섹션의 렌더링 블록을 청크로 나눔
 * 큰 블록 조각내기, 설명과 코드 묶음, target 기준 packing, 직전 짧은 문단 overlap 순서
 */
@Component
public class ChunkPackHelper {

    /** 청크를 닫는 기준 크기(UTF-8 바이트) */
    public static final int TARGET_BYTES = 6000;

    /** oversized가 아닌 청크와 조각의 최대 크기(UTF-8 바이트) */
    public static final int MAX_BYTES = 12000;

    /** 청크 하나의 최대 구성원 수 */
    public static final int MAX_BLOCKS = 32;

    /** overlap으로 붙일 수 있는 문단의 최대 크기(UTF-8 바이트) */
    public static final int OVERLAP_MAX_BYTES = 800;

    /** 이 크기를 넘는 표 caption과 header는 다음 조각에 반복하지 않음 */
    public static final int HEADER_REPEAT_MAX_BYTES = TARGET_BYTES / 2;

    private static final int SEPARATOR_BYTES = TextNormalizer
            .utf8Length(BlockRenderHelper.SEPARATOR);

    /** 섹션 하나의 청크 목록, 순서가 곧 섹션 안 청크 순번 */
    public List<ChunkDraft> pack(List<RenderedBlock> blocks) {

        List<ChunkPiece> pieces = blocks
                .stream()
                .flatMap(block -> pieces(block)
                        .stream())
                .toList();
        List<List<ChunkPiece>> chunks = new ArrayList<>();
        List<ChunkPiece> current = new ArrayList<>();

        for (List<ChunkPiece> bundle : bundles(pieces)) {
            boolean tooLarge = bundle
                    .size() > 1 && (bytes(bundle) > MAX_BYTES || bundle
                            .size() > MAX_BLOCKS);
            List<List<ChunkPiece>> items = tooLarge ? bundle
                    .stream()
                    .map(List::of)
                    .toList() : List
                            .of(bundle);

            for (List<ChunkPiece> item : items) {
                current = add(
                        chunks,
                        current,
                        item
                );
            }
        }

        if (!current
                .isEmpty()) {
            chunks
                    .add(current);
        }

        return withOverlap(chunks);
    }

    // 항목 하나 넣기, oversized는 혼자, target이나 32개를 넘으면 새 청크
    private static List<ChunkPiece> add(
            List<List<ChunkPiece>> chunks,
            List<ChunkPiece> current,
            List<ChunkPiece> item
    ) {

        boolean oversized = item
                .size() == 1 && item
                        .getFirst()
                        .oversized();

        if (oversized) {
            if (!current
                    .isEmpty()) {
                chunks
                        .add(current);
            }

            chunks
                    .add(List
                            .copyOf(item));

            return new ArrayList<>();
        }

        if (!current
                .isEmpty()) {
            int after = bytes(current) + SEPARATOR_BYTES + bytes(item);
            int count = current
                    .size() + item
                            .size();

            if (after > TARGET_BYTES || count > MAX_BLOCKS) {
                chunks
                        .add(current);
                current = new ArrayList<>();
            }
        }

        current
                .addAll(item);

        return current;
    }

    // 둘째 청크부터 직전 청크 끝의 짧은 완전 문단을 OVERLAP으로 앞에 붙임
    private static List<ChunkDraft> withOverlap(List<List<ChunkPiece>> chunks) {

        List<ChunkDraft> drafts = new ArrayList<>();
        int count = chunks
                .size();

        for (int index = 0; index < count; index++) {
            List<ChunkPiece> chunk = chunks
                    .get(index);
            List<ChunkMember> members = new ArrayList<>();

            if (index > 0) {
                ChunkPiece last = chunks
                        .get(index - 1)
                        .getLast();
                boolean wholeParagraph = last
                        .charStart() == null && !last
                                .oversized() && last
                                        .rendered()
                                        .block()
                                        .kind() == BlockKind.PARAGRAPH;
                boolean fits = last
                        .utf8Bytes() <= OVERLAP_MAX_BYTES && bytes(chunk) + SEPARATOR_BYTES + last
                                .utf8Bytes() <= MAX_BYTES && chunk
                                        .size() < MAX_BLOCKS;

                if (wholeParagraph && fits && !chunk
                        .getFirst()
                        .oversized()) {
                    members
                            .add(new ChunkMember(
                                    last,
                                    MemberRole.OVERLAP
                            ));
                }
            }

            chunk
                    .forEach(piece -> members
                            .add(new ChunkMember(
                                    piece,
                                    MemberRole.PRIMARY
                            )));
            drafts
                    .add(new ChunkDraft(members));
        }

        return drafts;
    }

    // 코드, 표, 탭 하나와 바로 앞 설명 하나, 바로 뒤 admonition 하나를 묶음
    private static List<List<ChunkPiece>> bundles(List<ChunkPiece> pieces) {

        int count = pieces
                .size();
        int[] group = new int[count];
        Arrays
                .fill(
                        group,
                        -1
                );

        for (int index = 0; index < count; index++) {
            if (!isAnchor(pieces
                    .get(index))) {
                continue;
            }

            if (index > 0 && group[index - 1] == -1 && isLead(pieces
                    .get(index - 1))) {
                group[index - 1] = index;
            }

            group[index] = index;

            if (index + 1 < count && isTrail(pieces
                    .get(index + 1))) {
                group[index + 1] = index;
                index++;
            }
        }

        List<List<ChunkPiece>> bundles = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            boolean joinsPrevious = index > 0 && group[index] != -1 && group[index] == group[index - 1];

            if (joinsPrevious) {
                bundles
                        .getLast()
                        .add(pieces
                                .get(index));
            } else {
                List<ChunkPiece> bundle = new ArrayList<>();
                bundle
                        .add(pieces
                                .get(index));
                bundles
                        .add(bundle);
            }
        }

        return bundles;
    }

    // 묶음 중심이 되는 완전한 코드, 표, 탭 그룹
    private static boolean isAnchor(ChunkPiece piece) {

        BlockKind kind = piece
                .rendered()
                .block()
                .kind();

        return isWhole(piece) && (kind == BlockKind.CODE || kind == BlockKind.TABLE || kind == BlockKind.TAB_GROUP);
    }

    // 묶음 앞에 붙는 완전한 문단이나 admonition
    private static boolean isLead(ChunkPiece piece) {

        BlockKind kind = piece
                .rendered()
                .block()
                .kind();

        return isWhole(piece) && (kind == BlockKind.PARAGRAPH || kind == BlockKind.ADMONITION);
    }

    // 묶음 뒤에 붙는 완전한 admonition
    private static boolean isTrail(ChunkPiece piece) {

        return isWhole(piece) && piece
                .rendered()
                .block()
                .kind() == BlockKind.ADMONITION;
    }

    // 조각이 아니고 oversized도 아닌 블록
    private static boolean isWhole(ChunkPiece piece) {

        return piece
                .charStart() == null && !piece
                        .oversized();
    }

    // 블록 하나의 조각 목록, 12000 이하는 블록 그대로
    private static List<ChunkPiece> pieces(RenderedBlock block) {

        if (block
                .utf8Bytes() <= MAX_BYTES) {
            return List
                    .of(whole(
                            block,
                            false
                    ));
        }

        return switch (block
                .block()
                .kind()) {
            case CODE, TAB_GROUP -> List
                    .of(whole(
                            block,
                            true
                    ));
            case TABLE -> tableSlices(block);
            case LIST -> lineSlices(block);
            case PARAGRAPH, ADMONITION -> textSlices(
                    block,
                    0,
                    block
                            .text()
                            .length(),
                    0
            );
        };
    }

    // 블록 전체 조각
    private static ChunkPiece whole(
            RenderedBlock block,
            boolean oversized
    ) {

        return new ChunkPiece(
                block,
                block
                        .text(),
                block
                        .utf8Bytes(),
                null,
                null,
                false,
                null,
                null,
                null,
                false,
                oversized,
                block
                        .inlineCodes(),
                block
                        .links()
        );
    }

    // 문자 범위 [from, to)를 문장 경계로 12000 이하 조각들로, 문장 하나가 넘으면 code point 경계
    private static List<ChunkPiece> textSlices(
            RenderedBlock block,
            int from,
            int to,
            int sliceIndex
    ) {

        String text = block
                .text();
        BreakIterator sentences = BreakIterator
                .getSentenceInstance(Locale.ROOT);
        sentences
                .setText(text
                        .substring(
                                from,
                                to
                        ));
        List<int[]> ranges = new ArrayList<>();
        int start = from;
        int end = from;
        int boundary = sentences
                .next();

        while (boundary != BreakIterator.DONE) {
            int sentenceEnd = from + boundary;

            if (TextNormalizer
                    .utf8Length(text
                            .substring(
                                    start,
                                    sentenceEnd
                            )) <= MAX_BYTES) {
                end = sentenceEnd;
            } else {
                if (end > start) {
                    ranges
                            .add(new int[] {start, end});
                    start = end;
                }

                if (TextNormalizer
                        .utf8Length(text
                                .substring(
                                        start,
                                        sentenceEnd
                                )) <= MAX_BYTES) {
                    end = sentenceEnd;
                } else {
                    start = cutByCodePoints(
                            text,
                            start,
                            sentenceEnd,
                            ranges
                    );
                    end = sentenceEnd;
                }
            }

            boundary = sentences
                    .next();
        }

        if (end > start) {
            ranges
                    .add(new int[] {start, end});
        }

        List<ChunkPiece> slices = new ArrayList<>();
        int index = sliceIndex;

        for (int[] range : ranges) {
            slices
                    .add(slice(
                            block,
                            range[0],
                            range[1],
                            index++ > 0
                    ));
        }

        return slices;
    }

    // [start, end)를 12000 이하 code point 조각으로 자르고 남은 시작 위치 반환
    private static int cutByCodePoints(
            String text,
            int start,
            int end,
            List<int[]> ranges
    ) {

        int sliceStart = start;
        int bytes = 0;
        int index = start;

        while (index < end) {
            int codePoint = text
                    .codePointAt(index);
            int width = TextNormalizer
                    .utf8Length(new String(Character
                            .toChars(codePoint)));

            if (bytes + width > MAX_BYTES) {
                ranges
                        .add(new int[] {sliceStart, index});
                sliceStart = index;
                bytes = 0;
            }

            bytes += width;
            index += Character
                    .charCount(codePoint);
        }

        return sliceStart;
    }

    // 목록 줄을 12000 이하로 묶은 조각, 줄 하나가 넘으면 그 줄만 문장 경계
    private static List<ChunkPiece> lineSlices(RenderedBlock block) {

        String text = block
                .text();
        List<ChunkPiece> slices = new ArrayList<>();
        int groupStart = -1;
        int groupEnd = -1;

        for (RenderUnit unit : block
                .units()) {
            int lineStart = text
                    .offsetByCodePoints(
                            0,
                            unit
                                    .charStart()
                    );
            int lineEnd = lineStart + unit
                    .text()
                    .length();

            if (TextNormalizer
                    .utf8Length(unit
                            .text()) > MAX_BYTES) {
                if (groupStart >= 0) {
                    slices
                            .add(slice(
                                    block,
                                    groupStart,
                                    groupEnd,
                                    !slices
                                            .isEmpty()
                            ));
                    groupStart = -1;
                }

                slices
                        .addAll(textSlices(
                                block,
                                lineStart,
                                lineEnd,
                                slices
                                        .size()
                        ));

                continue;
            }

            if (groupStart >= 0 && TextNormalizer
                    .utf8Length(text
                            .substring(
                                    groupStart,
                                    lineEnd
                            )) > MAX_BYTES) {
                slices
                        .add(slice(
                                block,
                                groupStart,
                                groupEnd,
                                !slices
                                        .isEmpty()
                        ));
                groupStart = -1;
            }

            if (groupStart < 0) {
                groupStart = lineStart;
            }

            groupEnd = lineEnd;
        }

        if (groupStart >= 0) {
            slices
                    .add(slice(
                            block,
                            groupStart,
                            groupEnd,
                            !slices
                                    .isEmpty()
                    ));
        }

        return slices;
    }

    // 표를 caption, header와 행 묶음으로 12000 이하 조각으로, 행 묶음 하나가 넘으면 그 묶음만 oversized
    private static List<ChunkPiece> tableSlices(RenderedBlock block) {

        String header = block
                .tableHeader();
        boolean repeat = !header
                .isEmpty() && TextNormalizer
                        .utf8Length(header) <= HEADER_REPEAT_MAX_BYTES;
        List<RenderUnit> units = block
                .units();
        List<ChunkPiece> slices = new ArrayList<>();
        int index = 0;
        int count = units
                .size();

        while (index < count) {
            boolean first = slices
                    .isEmpty();
            String prefix = first || repeat ? header : "";
            StringBuilder text = new StringBuilder(prefix);
            List<RenderUnit> group = new ArrayList<>();

            while (index < count) {
                RenderUnit unit = units
                        .get(index);
                String candidate = text
                        .isEmpty() ? unit
                                .text() : text + "\n" + unit
                                        .text();

                if (!group
                        .isEmpty() && TextNormalizer
                                .utf8Length(candidate) > MAX_BYTES) {
                    break;
                }

                text
                        .setLength(0);
                text
                        .append(candidate);
                group
                        .add(unit);
                index++;

                if (TextNormalizer
                        .utf8Length(candidate) > MAX_BYTES) {
                    break;
                }
            }

            RenderUnit firstUnit = group
                    .getFirst();
            RenderUnit lastUnit = group
                    .getLast();
            int charStart = first ? 0 : firstUnit
                    .charStart();
            int charEnd = lastUnit
                    .charStart() + lastUnit
                            .text()
                            .codePointCount(
                                    0,
                                    lastUnit
                                            .text()
                                            .length()
                            );
            String sliceText = text
                    .toString();
            int bytes = TextNormalizer
                    .utf8Length(sliceText);

            slices
                    .add(new ChunkPiece(
                            block,
                            sliceText,
                            bytes,
                            charStart,
                            charEnd,
                            !first,
                            firstUnit
                                    .rowStart(),
                            lastUnit
                                    .rowEndExclusive(),
                            block
                                    .totalRows(),
                            !first && repeat,
                            bytes > MAX_BYTES,
                            block
                                    .inlineCodes()
                                    .stream()
                                    .filter(code -> code
                                            .start() >= charStart && code
                                                    .start() < charEnd)
                                    .toList(),
                            block
                                    .links()
                                    .stream()
                                    .filter(link -> link
                                            .start() >= charStart && link
                                                    .start() < charEnd)
                                    .toList()
                    ));
        }

        return slices;
    }

    // 렌더링 텍스트의 문자 범위 [from, to) 조각, 위치는 code point
    private static ChunkPiece slice(
            RenderedBlock block,
            int from,
            int to,
            boolean continuation
    ) {

        String text = block
                .text();
        String sliceText = text
                .substring(
                        from,
                        to
                );
        int charStart = text
                .codePointCount(
                        0,
                        from
                );
        int charEnd = charStart + sliceText
                .codePointCount(
                        0,
                        sliceText
                                .length()
                );

        return new ChunkPiece(
                block,
                sliceText,
                TextNormalizer
                        .utf8Length(sliceText),
                charStart,
                charEnd,
                continuation,
                null,
                null,
                null,
                false,
                false,
                block
                        .inlineCodes()
                        .stream()
                        .filter(code -> code
                                .start() >= charStart && code
                                        .start() < charEnd)
                        .toList(),
                block
                        .links()
                        .stream()
                        .filter(link -> link
                                .start() >= charStart && link
                                        .start() < charEnd)
                        .toList()
        );
    }

    // 조각 목록의 구분자 포함 바이트 수
    private static int bytes(List<ChunkPiece> pieces) {

        int total = 0;

        for (ChunkPiece piece : pieces) {
            total += piece
                    .utf8Bytes();
        }

        return total + SEPARATOR_BYTES * Math
                .max(
                        0,
                        pieces
                                .size() - 1
                );
    }
}
