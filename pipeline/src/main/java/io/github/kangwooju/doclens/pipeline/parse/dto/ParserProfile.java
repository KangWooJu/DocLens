package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;
import io.github.kangwooju.doclens.pipeline.parse.type.DefaultTabRule;
import io.github.kangwooju.doclens.pipeline.parse.type.VersionMarkerRule;

/**
 * 출처별 본문 선택 규칙, 사람이 실제 표본과 대조한 뒤 ENABLED
 * urlPattern은 전체 일치 정규식, canonicalLabels는 탭 라벨 원문에서 정식 라벨로의 대응표
 * preferredDefaultLabels는 서버 표식이 없을 때 기본 탭으로 먼저 고를 정식 라벨 순서
 * asciidoc은 AsciiDoc 원본을 HTML로 바꾸는 규칙이고 HTML 원본 프로필은 null
 */
public record ParserProfile(
        String profileId,
        String profileVersion,
        String project,
        DocumentType documentType,
        String urlPattern,
        String rootSelector,
        List<String> excludedSelectors,
        List<String> headingSelectors,
        TabSelectors tabSelectors,
        VersionMarkerRule versionMarkerRule,
        List<String> fixtureHashes,
        ApprovalState approvalState,
        DefaultTabRule defaultTabRule,
        List<String> preferredDefaultLabels,
        Map<String, String> canonicalLabels,
        Map<VariantGroupType, Set<String>> groupTypes,
        AsciidocRules asciidoc
) {

    /** 목록과 대응표 불변 사본 */
    public ParserProfile {
        excludedSelectors = List
                .copyOf(excludedSelectors);
        headingSelectors = List
                .copyOf(headingSelectors);
        fixtureHashes = List
                .copyOf(fixtureHashes);
        preferredDefaultLabels = List
                .copyOf(preferredDefaultLabels);
        canonicalLabels = Map
                .copyOf(canonicalLabels);
        groupTypes = Map
                .copyOf(groupTypes);
    }
}
