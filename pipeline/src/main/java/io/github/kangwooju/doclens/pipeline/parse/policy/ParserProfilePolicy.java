package io.github.kangwooju.doclens.pipeline.parse.policy;

import java.text.Normalizer;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.DefaultVariantChoice;
import io.github.kangwooju.doclens.pipeline.parse.dto.ExtractedDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructureIssue;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredSection;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabVariant;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableCell;
import io.github.kangwooju.doclens.pipeline.parse.dto.TableRow;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;
import io.github.kangwooju.doclens.pipeline.parse.type.DefaultTabRule;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

/** 프로필 승인, 버전 표식, 본문 root 개수, 순회 결과 구조, 기본 탭 판정 */
@Component
public class ParserProfilePolicy {

    /** 서버 표식으로 기본 탭을 정했을 때의 근거 */
    public static final String SERVER_MARKER = "SERVER_MARKER";

    /** 코드 블록과 탭 변형 하나의 최대 UTF-8 바이트 */
    public static final int MAX_CODE_BYTES = 1024 * 1024;

    /** ENABLED가 아닌 프로필은 보류 */
    public void requireEnabled(ParserProfile profile) {

        if (profile
                .approvalState() != ApprovalState.ENABLED) {
            throw new DocumentHoldException(
                    HoldReason.SELECTOR_UNVERIFIED,
                    "profile " + profile
                            .profileId() + " is " + profile
                            .approvalState()
            );
        }
    }

    /** 문서 버전 표식이 정확히 하나이고 요청 버전과 같아야 통과 */
    public void requireVersion(
            List<String> markers,
            String exactVersion
    ) {

        List<String> distinct = markers
                .stream()
                .distinct()
                .toList();

        if (distinct
                .isEmpty()) {
            throw new DocumentHoldException(
                    HoldReason.VERSION_MARKER_MISSING,
                    "no version marker"
            );
        }

        if (distinct
                .size() > 1 || !distinct
                        .getFirst()
                        .equals(exactVersion)) {
            throw new DocumentHoldException(
                    HoldReason.VERSION_MARKER_CONFLICT,
                    "markers " + distinct + " for version " + exactVersion
            );
        }
    }

    /** 본문 root가 정확히 하나여야 통과 */
    public void requireSingleRoot(
            int rootCount,
            String rootSelector
    ) {

        if (rootCount != 1) {
            throw new DocumentHoldException(
                    HoldReason.ROOT_AMBIGUOUS,
                    rootCount + " elements match " + rootSelector
            );
        }
    }

    /**
     * 순회 결과를 그대로 쓸 수 있어야 통과
     * 건너뛴 구조, 첫 heading 없음 또는 level 1 아님, 블록 없음, NFC 아닌 텍스트, 1 MiB 넘는 코드나 탭 변형은 보류
     */
    public void requireSupported(ExtractedDocument extracted) {

        if (!extracted
                .issues()
                .isEmpty()) {
            StructureIssue issue = extracted
                    .issues()
                    .getFirst();

            throw new DocumentHoldException(
                    holdReasonOf(issue),
                    issue
                            .type() + " at " + issue
                                    .domPath()
            );
        }

        List<StructuredSection> sections = extracted
                .sections();

        if (sections
                .isEmpty()) {
            throw new DocumentHoldException(
                    HoldReason.TITLE_MISSING,
                    "no heading in body"
            );
        }

        if (sections
                .getFirst()
                .level() != 1) {
            throw new DocumentHoldException(
                    HoldReason.TITLE_MISSING,
                    "first heading is level " + sections
                            .getFirst()
                            .level()
            );
        }

        if (sections
                .stream()
                .allMatch(section -> section
                        .blocks()
                        .isEmpty())) {
            throw new DocumentHoldException(
                    HoldReason.BODY_EMPTY,
                    "no block in body"
            );
        }

        for (StructuredSection section : sections) {
            requireSupportedBlocks(section
                    .blocks());
        }
    }

    /**
     * 서버 표식 1개면 그 변형, 0개면 프로필 선호 라벨 순서, 없으면 프로필 규칙, 그 밖은 보류
     * 선호 라벨은 정식 라벨과 비교하고 같은 라벨이 여럿이면 원문 순서 첫 변형
     */
    public DefaultVariantChoice resolveDefault(
            ParserProfile profile,
            TabGroup group
    ) {

        List<Integer> markedOrdinals = group
                .markedOrdinals();

        if (markedOrdinals
                .size() == 1) {
            return new DefaultVariantChoice(
                    markedOrdinals
                            .getFirst(),
                    SERVER_MARKER
            );
        }

        if (markedOrdinals
                .isEmpty()) {
            for (String preferred : profile
                    .preferredDefaultLabels()) {
                for (TabVariant variant : group
                        .variants()) {
                    if (variant
                            .canonicalLabel()
                            .equals(preferred)) {
                        return new DefaultVariantChoice(
                                variant
                                        .ordinal(),
                                "PROFILE_RULE:PREFERRED_LABEL:" + preferred
                        );
                    }
                }
            }

            if (profile
                    .defaultTabRule() == DefaultTabRule.FIRST_IN_SOURCE_ORDER) {
                return new DefaultVariantChoice(
                        0,
                        "PROFILE_RULE:" + DefaultTabRule.FIRST_IN_SOURCE_ORDER
                );
            }
        }

        throw new DocumentHoldException(
                HoldReason.TAB_DEFAULT_UNRESOLVED,
                markedOrdinals
                        .size() + " selected markers in tab group " + group
                                .groupLocator()
        );
    }

    // 건너뛴 구조 종류별 보류 이유
    private static HoldReason holdReasonOf(StructureIssue issue) {

        return switch (issue
                .type()) {
            case CONTENT_BEFORE_HEADING -> HoldReason.TITLE_MISSING;
            case NESTED_TAB_GROUP,
                    TAB_GROUP_UNKNOWN_CHILD,
                    TAB_GROUP_WITHOUT_TABS,
                    TAB_PANEL_MISMATCH -> HoldReason.TAB_STRUCTURE_UNSUPPORTED;
            case BLOCK_IN_LIST,
                    BLOCK_IN_TABLE_CELL -> HoldReason.BLOCK_NESTING_UNSUPPORTED;
        };
    }

    // 블록 텍스트 NFC, 코드 크기, 탭 변형 크기 확인
    private static void requireSupportedBlocks(List<Block> blocks) {

        for (Block block : blocks) {
            requireNfc(block);

            if (block
                    .kind() == BlockKind.CODE && TextNormalizer
                            .utf8Length(block
                                    .text()) > MAX_CODE_BYTES) {
                throw new DocumentHoldException(
                        HoldReason.CODE_OVER_HARD_LIMIT,
                        "code over 1 MiB at " + block
                                .sourcePointer()
                                .domPath()
                );
            }

            if (block
                    .tabGroup() == null) {
                continue;
            }

            for (TabVariant variant : block
                    .tabGroup()
                    .variants()) {
                requireSupportedBlocks(variant
                        .blocks());

                if (utf8Length(variant
                        .blocks()) > MAX_CODE_BYTES) {
                    throw new DocumentHoldException(
                            HoldReason.CODE_OVER_HARD_LIMIT,
                            "tab variant over 1 MiB at " + variant
                                    .sourcePointer()
                                    .domPath()
                    );
                }
            }
        }
    }

    // 여러 인라인 요소에 걸쳐 결합 문자가 나뉘어 NFC가 아닌 블록 텍스트는 보류
    private static void requireNfc(Block block) {

        if (block
                .text() != null && !Normalizer
                        .isNormalized(
                                block
                                        .text(),
                                Normalizer.Form.NFC
                        )) {
            throw new DocumentHoldException(
                    HoldReason.INVALID_UNICODE,
                    "combining character split across inline elements at " + block
                            .sourcePointer()
                            .domPath()
            );
        }
    }

    // 블록 텍스트와 표 셀 텍스트의 UTF-8 바이트 합
    private static long utf8Length(List<Block> blocks) {

        long bytes = 0;

        for (Block block : blocks) {
            if (block
                    .text() != null) {
                bytes += TextNormalizer
                        .utf8Length(block
                                .text());
            }

            if (block
                    .table() == null) {
                continue;
            }

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

        return bytes;
    }
}
