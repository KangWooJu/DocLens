package io.github.kangwooju.doclens.pipeline.parse.policy;

import java.util.List;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.parse.dto.DefaultVariantChoice;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;
import io.github.kangwooju.doclens.pipeline.parse.type.DefaultTabRule;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;

/** 프로필 승인, 버전 표식, 본문 root 개수, 기본 탭 판정 */
@Component
public class ParserProfilePolicy {

    /** 서버 표식으로 기본 탭을 정했을 때의 근거 */
    public static final String SERVER_MARKER = "SERVER_MARKER";

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

    /** 서버 표식 1개면 그 변형, 0개면 프로필 규칙, 그 밖은 보류 */
    public DefaultVariantChoice resolveDefault(
            ParserProfile profile,
            List<Integer> markedOrdinals,
            String groupLocator
    ) {

        if (markedOrdinals
                .size() == 1) {
            return new DefaultVariantChoice(
                    markedOrdinals
                            .getFirst(),
                    SERVER_MARKER
            );
        }

        if (markedOrdinals
                .isEmpty() && profile
                        .defaultTabRule() == DefaultTabRule.FIRST_IN_SOURCE_ORDER) {
            return new DefaultVariantChoice(
                    0,
                    "PROFILE_RULE:" + DefaultTabRule.FIRST_IN_SOURCE_ORDER
            );
        }

        throw new DocumentHoldException(
                HoldReason.TAB_DEFAULT_UNRESOLVED,
                markedOrdinals
                        .size() + " selected markers in tab group " + groupLocator
        );
    }
}
