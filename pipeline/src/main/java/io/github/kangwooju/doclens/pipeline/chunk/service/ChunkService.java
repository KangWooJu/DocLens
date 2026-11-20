package io.github.kangwooju.doclens.pipeline.chunk.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.core.pack.dto.CodeVariant;
import io.github.kangwooju.doclens.core.pack.dto.DocLink;
import io.github.kangwooju.doclens.core.pack.dto.PackChunk;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackSection;
import io.github.kangwooju.doclens.pipeline.chunk.converter.ChunkJsonConverter;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkDraft;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkMember;
import io.github.kangwooju.doclens.pipeline.chunk.dto.RenderedBlock;
import io.github.kangwooju.doclens.pipeline.chunk.helper.BlockRenderHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.ChunkPackHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.IdentifierHelper;
import io.github.kangwooju.doclens.pipeline.chunk.type.MemberRole;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;
import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredSection;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabVariant;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;
import lombok.RequiredArgsConstructor;

/**
 * 구조 산출물을 Pack 문서로 바꾸는 CHUNK 단계
 * 섹션마다 렌더링, packing 뒤 청크 ID, 코드 변형, 식별자, 문서 링크 생성, 보류 없음
 */
@Service
@RequiredArgsConstructor
public class ChunkService {

    /** 청커 정책 버전, 문장 경계가 JDK에 묶여 JDK 주 버전을 포함 */
    public static final String CHUNKER_POLICY_VERSION = "chunker-v2-jdk" + Runtime
            .version()
            .feature();

    private final BlockRenderHelper blockRenderHelper;
    private final ChunkPackHelper chunkPackHelper;
    private final IdentifierHelper identifierHelper;
    private final ChunkJsonConverter chunkJsonConverter;

    /** 구조 산출물 하나의 Pack 문서, 같은 입력이면 같은 결과 */
    public PackDocument chunk(StructuredDocument document) {

        String selfUrl = withoutFragment(document
                .provenance()
                .finalUrl());
        List<PackSection> sections = document
                .sections()
                .stream()
                .map(section -> new PackSection(
                        section
                                .sectionId(),
                        section
                                .parentSectionId(),
                        section
                                .anchor(),
                        section
                                .headingPath(),
                        section
                                .ordinal(),
                        chunks(
                                document,
                                section,
                                selfUrl
                        )
                ))
                .toList();

        return new PackDocument(
                document
                        .documentId(),
                document
                        .canonicalUrl(),
                document
                        .title(),
                document
                        .documentType(),
                document
                        .exactVersion(),
                document
                        .provenance()
                        .sourceSnapshotId(),
                document
                        .provenance()
                        .rawSha256(),
                chunkJsonConverter
                        .documentSourceMapJson(
                                document
                                        .provenance(),
                                CHUNKER_POLICY_VERSION
                        ),
                sections
        );
    }

    // 섹션 하나의 청크 목록
    private List<PackChunk> chunks(
            StructuredDocument document,
            StructuredSection section,
            String selfUrl
    ) {

        List<RenderedBlock> rendered = section
                .blocks()
                .stream()
                .map(blockRenderHelper::render)
                .toList();
        List<ChunkDraft> drafts = chunkPackHelper
                .pack(rendered);
        List<PackChunk> chunks = new ArrayList<>();
        int count = drafts
                .size();

        for (int ordinal = 0; ordinal < count; ordinal++) {
            chunks
                    .add(chunk(
                            document,
                            section,
                            drafts
                                    .get(ordinal)
                                    .members(),
                            ordinal,
                            selfUrl
                    ));
        }

        return chunks;
    }

    // 청크 하나, content는 구성원 텍스트를 구분자로 이은 것
    private PackChunk chunk(
            StructuredDocument document,
            StructuredSection section,
            List<ChunkMember> members,
            int ordinal,
            String selfUrl
    ) {

        StringBuilder content = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();

        for (ChunkMember member : members) {
            if (!content
                    .isEmpty()) {
                content
                        .append(BlockRenderHelper.SEPARATOR);
            }

            int start = codePoints(content);
            content
                    .append(member
                            .piece()
                            .text());
            ranges
                    .add(new int[] {start, codePoints(content)});
        }

        String text = content
                .toString();

        if (!TextNormalizer
                .normalize(text)
                .equals(text)) {
            throw new IllegalStateException("chunk content is not NFC with LF line endings in " + section
                    .sectionId());
        }

        List<String> tabLocators = new ArrayList<>();
        List<String> defaultKeys = new ArrayList<>();
        List<CodeVariant> codeVariants = new ArrayList<>();
        int memberCount = members
                .size();

        for (int index = 0; index < memberCount; index++) {
            ChunkMember member = members
                    .get(index);
            Block block = member
                    .piece()
                    .rendered()
                    .block();

            if (member
                    .role() != MemberRole.PRIMARY || block
                            .kind() != BlockKind.TAB_GROUP) {
                continue;
            }

            TabGroup group = block
                    .tabGroup();
            tabLocators
                    .add(group
                            .groupLocator());
            defaultKeys
                    .add(group
                            .defaultVariant());

            for (TabVariant variant : group
                    .variants()) {
                codeVariants
                        .add(new CodeVariant(
                                index,
                                group
                                        .groupType(),
                                variant
                                        .variantKey(),
                                variant
                                        .ordinal(),
                                variant
                                        .variantKey()
                                        .equals(group
                                                .defaultVariant()),
                                blockRenderHelper
                                        .variantContent(variant),
                                chunkJsonConverter
                                        .variantSourceMapJson(
                                                block,
                                                variant
                                        )
                        ));
            }
        }

        return new PackChunk(
                IdHash
                        .chunkId(
                                document
                                        .documentId(),
                                section
                                        .headingLocator(),
                                IdHash
                                        .tabGroupLocatorOf(tabLocators),
                                IdHash
                                        .variantKeyOf(defaultKeys),
                                ordinal,
                                text
                        ),
                ordinal,
                text,
                String
                        .join(
                                " > ",
                                section
                                        .headingPath()
                        ),
                chunkJsonConverter
                        .blocksJson(
                                members,
                                ranges
                        ),
                chunkJsonConverter
                        .sourceMapJson(
                                members,
                                section
                                        .headingPath()
                        ),
                codeVariants,
                identifierHelper
                        .identifiers(members),
                docLinks(
                        members,
                        selfUrl
                )
        );
    }

    // PRIMARY 구성원의 다른 문서 링크, 중복 없이 주소와 anchor 순
    private static List<DocLink> docLinks(
            List<ChunkMember> members,
            String selfUrl
    ) {

        return members
                .stream()
                .filter(member -> member
                        .role() == MemberRole.PRIMARY)
                .flatMap(member -> member
                        .piece()
                        .links()
                        .stream())
                .filter(link -> link
                        .targetUrl() != null && !link
                                .targetUrl()
                                .equals(selfUrl))
                .map(link -> new DocLink(
                        link
                                .targetUrl(),
                        link
                                .targetAnchor()
                ))
                .distinct()
                .sorted(Comparator
                        .comparing(DocLink::targetUrl)
                        .thenComparing(DocLink::targetAnchor))
                .toList();
    }

    // fragment를 뗀 주소
    private static String withoutFragment(String url) {

        int hash = url
                .indexOf('#');

        return hash < 0 ? url : url
                .substring(
                        0,
                        hash
                );
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
