package io.github.kangwooju.doclens.pipeline.chunk.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkMember;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkPiece;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.BlockLink;
import io.github.kangwooju.doclens.pipeline.parse.dto.Provenance;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourcePointer;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabVariant;

/** Pack의 blocksJson, sourceMapJson 열 값, 모두 canonical JSON */
@Component
public class ChunkJsonConverter {

    /** 청크 구성원 배열, contentRanges는 구성원마다 content 안 [시작, 끝) code point */
    public String blocksJson(
            List<ChunkMember> members,
            List<int[]> contentRanges
    ) {

        List<Object> entries = new ArrayList<>();
        int count = members
                .size();

        for (int index = 0; index < count; index++) {
            ChunkMember member = members
                    .get(index);
            ChunkPiece piece = member
                    .piece();
            Block block = piece
                    .rendered()
                    .block();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry
                    .put(
                            "blockId",
                            block
                                    .blockId()
                    );
            entry
                    .put(
                            "kind",
                            block
                                    .kind()
                                    .name()
                    );
            entry
                    .put(
                            "ordinal",
                            block
                                    .ordinal()
                    );
            entry
                    .put(
                            "role",
                            member
                                    .role()
                                    .name()
                    );
            entry
                    .put(
                            "contentStart",
                            contentRanges
                                    .get(index)[0]
                    );
            entry
                    .put(
                            "contentEnd",
                            contentRanges
                                    .get(index)[1]
                    );
            entry
                    .put(
                            "language",
                            block
                                    .language()
                    );
            entry
                    .put(
                            "title",
                            block
                                    .title()
                    );
            entry
                    .put(
                            "charStart",
                            piece
                                    .charStart()
                    );
            entry
                    .put(
                            "charEnd",
                            piece
                                    .charEnd()
                    );
            entry
                    .put(
                            "continuation",
                            piece
                                    .continuation()
                    );
            entry
                    .put(
                            "rowStart",
                            piece
                                    .rowStart()
                    );
            entry
                    .put(
                            "rowEndExclusive",
                            piece
                                    .rowEndExclusive()
                    );
            entry
                    .put(
                            "totalRows",
                            piece
                                    .totalRows()
                    );
            entry
                    .put(
                            "headerRepeated",
                            piece
                                    .headerRepeated()
                    );
            entry
                    .put(
                            "oversized",
                            piece
                                    .oversized()
                    );
            entry
                    .put(
                            "tabGroup",
                            tabGroup(block
                                    .tabGroup())
                    );
            entries
                    .add(entry);
        }

        return CanonicalJson
                .write(entries);
    }

    /** 청크 구성원별 원본 위치와 구성원에 든 링크 */
    public String sourceMapJson(
            List<ChunkMember> members,
            List<String> headingPath
    ) {

        List<Object> entries = new ArrayList<>();

        for (ChunkMember member : members) {
            ChunkPiece piece = member
                    .piece();
            Block block = piece
                    .rendered()
                    .block();
            SourcePointer pointer = block
                    .sourcePointer();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry
                    .put(
                            "blockId",
                            block
                                    .blockId()
                    );
            entry
                    .put(
                            "domPath",
                            pointer
                                    .domPath()
                    );
            entry
                    .put(
                            "elementId",
                            pointer
                                    .elementId()
                    );
            entry
                    .put(
                            "headingLocator",
                            pointer
                                    .headingLocator()
                    );
            entry
                    .put(
                            "headingPath",
                            headingPath
                    );
            entry
                    .put(
                            "blockOrdinal",
                            pointer
                                    .blockOrdinal()
                    );
            entry
                    .put(
                            "charStart",
                            piece
                                    .charStart()
                    );
            entry
                    .put(
                            "charEnd",
                            piece
                                    .charEnd()
                    );
            entry
                    .put(
                            "links",
                            piece
                                    .links()
                                    .stream()
                                    .map(ChunkJsonConverter::link)
                                    .toList()
                    );
            entries
                    .add(entry);
        }

        return CanonicalJson
                .write(entries);
    }

    /** 문서 단위 출처와 정책 버전 */
    public String documentSourceMapJson(
            Provenance provenance,
            String chunkerPolicyVersion
    ) {

        Map<String, Object> source = new LinkedHashMap<>();
        source
                .put(
                        "rawSha256",
                        provenance
                                .rawSha256()
                );
        source
                .put(
                        "sourceSnapshotId",
                        provenance
                                .sourceSnapshotId()
                );
        source
                .put(
                        "originalUrl",
                        provenance
                                .originalUrl()
                );
        source
                .put(
                        "finalUrl",
                        provenance
                                .finalUrl()
                );
        source
                .put(
                        "profileId",
                        provenance
                                .profileId()
                );
        source
                .put(
                        "profileVersion",
                        provenance
                                .profileVersion()
                );
        source
                .put(
                        "parserPolicyVersion",
                        provenance
                                .parserPolicyVersion()
                );
        source
                .put(
                        "chunkerPolicyVersion",
                        chunkerPolicyVersion
                );
        source
                .put(
                        "transformationVersion",
                        provenance
                                .parserPolicyVersion() + "+" + chunkerPolicyVersion
                );

        return CanonicalJson
                .write(source);
    }

    /** 탭 변형 하나의 원본 위치 */
    public String variantSourceMapJson(
            Block groupBlock,
            TabVariant variant
    ) {

        Map<String, Object> source = new LinkedHashMap<>();
        source
                .put(
                        "blockId",
                        groupBlock
                                .blockId()
                );
        source
                .put(
                        "domPath",
                        variant
                                .sourcePointer()
                                .domPath()
                );
        source
                .put(
                        "elementId",
                        variant
                                .sourcePointer()
                                .elementId()
                );
        source
                .put(
                        "label",
                        variant
                                .label()
                );

        return CanonicalJson
                .write(source);
    }

    // 탭 그룹 요약, 탭이 아니면 null
    private static Map<String, Object> tabGroup(TabGroup group) {

        if (group == null) {
            return null;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry
                .put(
                        "groupLocator",
                        group
                                .groupLocator()
                );
        entry
                .put(
                        "groupType",
                        group
                                .groupType()
                                .wireValue()
                );
        entry
                .put(
                        "defaultVariant",
                        group
                                .defaultVariant()
                );
        entry
                .put(
                        "defaultEvidence",
                        group
                                .defaultEvidence()
                );
        entry
                .put(
                        "variants",
                        group
                                .variants()
                                .stream()
                                .map(variant -> {
                                    Map<String, Object> value = new LinkedHashMap<>();
                                    value
                                            .put(
                                                    "variant",
                                                    variant
                                                            .variantKey()
                                            );
                                    value
                                            .put(
                                                    "label",
                                                    variant
                                                            .label()
                                            );
                                    value
                                            .put(
                                                    "ordinal",
                                                    variant
                                                            .ordinal()
                                            );

                                    return value;
                                })
                                .toList()
        );

        return entry;
    }

    // 링크 한 개
    private static Map<String, Object> link(BlockLink link) {

        Map<String, Object> entry = new LinkedHashMap<>();
        entry
                .put(
                        "label",
                        link
                                .label()
                );
        entry
                .put(
                        "href",
                        link
                                .href()
                );
        entry
                .put(
                        "targetUrl",
                        link
                                .targetUrl()
                );
        entry
                .put(
                        "targetAnchor",
                        link
                                .targetAnchor()
                );

        return entry;
    }
}
