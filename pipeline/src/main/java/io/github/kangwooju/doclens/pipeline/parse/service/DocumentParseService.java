package io.github.kangwooju.doclens.pipeline.parse.service;

import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.pipeline.global.id.TextNormalizer;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocHtml;
import io.github.kangwooju.doclens.pipeline.parse.dto.AsciidocRules;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.DefaultVariantChoice;
import io.github.kangwooju.doclens.pipeline.parse.dto.ExtractedDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.Provenance;
import io.github.kangwooju.doclens.pipeline.parse.dto.SectionCitation;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredSection;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.helper.AsciidocHtmlHelper;
import io.github.kangwooju.doclens.pipeline.parse.helper.BlockExtractHelper;
import io.github.kangwooju.doclens.pipeline.parse.policy.ParserProfilePolicy;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;
import io.github.kangwooju.doclens.pipeline.parse.type.VersionMarkerRule;
import lombok.RequiredArgsConstructor;

/**
 * 원본 HTML이나 AsciiDoc 하나를 구조 산출물로 만드는 PARSE 단계
 * 원본 hash, UTF-8, 프로필, 버전 표식, 본문 root 확인 뒤 순회, 순회 결과도 정책으로 확인, 맞지 않으면 보류
 * AsciiDoc 프로필이면 같은 Build의 include로 HTML을 만든 뒤 같은 경로로 읽고 섹션마다 인용 주소를 붙임
 */
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    /** 파서 정책 버전, 규칙이 바뀌면 올림 */
    public static final String PARSER_POLICY_VERSION = "parser-v4";

    private final ParserProfileStore parserProfileStore;
    private final ParserProfilePolicy parserProfilePolicy;
    private final BlockExtractHelper blockExtractHelper;
    private final AsciidocHtmlHelper asciidocHtmlHelper;

    /** include 없는 원본 하나의 구조 산출물 */
    public StructuredDocument parse(SourceDocument source) {

        return parse(
                source,
                Map
                        .of()
        );
    }

    /**
     * 원본 하나의 구조 산출물, 보류는 DocumentHoldException, 입력 결함은 IllegalArgumentException
     * includes는 같은 Build의 hash 검증된 include 원본, key는 canonicalUrl
     */
    public StructuredDocument parse(
            SourceDocument source,
            Map<String, SourceDocument> includes
    ) {

        requireFields(source);
        requireRawHash(source);

        String html = decode(source);
        ParserProfile profile = parserProfileStore
                .find(
                        source
                                .project(),
                        source
                                .documentType(),
                        source
                                .finalUrl()
                )
                .orElseThrow(() -> new DocumentHoldException(
                        HoldReason.SELECTOR_UNVERIFIED,
                        "no parser profile for " + source
                                .finalUrl()
                ));

        parserProfilePolicy
                .requireEnabled(profile);

        AsciidocHtml asciidoc = null;

        if (profile
                .asciidoc() != null) {
            asciidoc = asciidocHtmlHelper
                    .html(
                            source,
                            profile
                                    .asciidoc(),
                            includes
                    );

            parserProfilePolicy
                    .requireResolved(asciidoc
                            .unresolved());

            html = asciidoc
                    .html();
        }

        Document document = Jsoup
                .parse(
                        html,
                        source
                                .finalUrl()
                );

        if (profile
                .versionMarkerRule() == VersionMarkerRule.ANTORA_META_VERSION) {
            parserProfilePolicy
                    .requireVersion(
                            document
                                    .select("meta[name=version]")
                                    .eachAttr("content"),
                            source
                                    .exactVersion()
                    );
        }

        Elements roots = document
                .select(profile
                        .rootSelector());

        parserProfilePolicy
                .requireSingleRoot(
                        roots
                                .size(),
                        profile
                                .rootSelector()
                );

        Element root = roots
                .getFirst();
        ExtractedDocument extracted = blockExtractHelper
                .extract(
                        root,
                        profile,
                        source
                                .documentId(),
                        source
                                .finalUrl()
                );

        parserProfilePolicy
                .requireSupported(extracted);

        return new StructuredDocument(
                StructuredDocument.SCHEMA_VERSION,
                source
                        .documentId(),
                source
                        .project(),
                source
                        .exactVersion(),
                source
                        .documentType(),
                source
                        .canonicalUrl(),
                extracted
                        .title(),
                new Provenance(
                        source
                                .rawSha256(),
                        source
                                .sourceSnapshotId(),
                        source
                                .originalUrl(),
                        source
                                .finalUrl(),
                        profile
                                .profileId(),
                        profile
                                .profileVersion(),
                        PARSER_POLICY_VERSION,
                        asciidoc == null ? List
                                .of() : asciidoc
                                        .includes()
                ),
                withDefaultVariants(
                        extracted
                                .sections(),
                        profile,
                        source,
                        asciidoc
                )
        );
    }

    // 필수 입력이 모두 있어야 통과, 빠지면 입력 오류
    private static void requireFields(SourceDocument source) {

        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }

        List<Object> required = Arrays
                .asList(
                        source
                                .documentId(),
                        source
                                .project(),
                        source
                                .exactVersion(),
                        source
                                .documentType(),
                        source
                                .canonicalUrl(),
                        source
                                .originalUrl(),
                        source
                                .finalUrl(),
                        source
                                .sourceSnapshotId(),
                        source
                                .rawSha256(),
                        source
                                .rawBytes()
                );

        if (required
                .contains(null)) {
            throw new IllegalArgumentException("every source field is required");
        }
    }

    // 원본 바이트의 SHA-256이 rawSha256과 같아야 통과
    private static void requireRawHash(SourceDocument source) {

        try {
            String actual = "sha256:" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(source
                                    .rawBytes()));

            if (!actual
                    .equals(source
                            .rawSha256())) {
                throw new IllegalArgumentException("rawSha256 does not match the raw bytes of " + source
                        .documentId());
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java platform",
                    exception
            );
        }
    }

    // 엄격한 UTF-8 해석, 실패하면 보류
    private static String decode(SourceDocument source) {

        try {
            return TextNormalizer
                    .decodeUtf8(source
                            .rawBytes());
        } catch (CharacterCodingException exception) {
            throw new DocumentHoldException(
                    HoldReason.INVALID_UNICODE,
                    "raw bytes are not valid UTF-8"
            );
        }
    }

    // 탭 그룹마다 정책으로 기본 변형을 정하고 AsciiDoc 원본이면 인용 주소를 붙여 섹션 다시 만들기
    private List<StructuredSection> withDefaultVariants(
            List<StructuredSection> sections,
            ParserProfile profile,
            SourceDocument source,
            AsciidocHtml asciidoc
    ) {

        return sections
                .stream()
                .map(section -> new StructuredSection(
                        section
                                .sectionId(),
                        section
                                .parentSectionId(),
                        section
                                .headingLocator(),
                        section
                                .anchor(),
                        section
                                .headingPath(),
                        section
                                .level(),
                        section
                                .ordinal(),
                        withDefaultVariant(
                                section
                                        .blocks(),
                                profile
                        ),
                        asciidoc == null ? null : citation(
                                section,
                                profile
                                        .asciidoc(),
                                source,
                                asciidoc
                        )
                ))
                .toList();
    }

    // 섹션 인용 두 주소, 읽기는 문서 사이트 major.minor 쪽과 anchor, 원본은 commit 고정 원문 보기와 id 줄이나 제목 줄
    private static SectionCitation citation(
            StructuredSection section,
            AsciidocRules rules,
            SourceDocument source,
            AsciidocHtml asciidoc
    ) {

        String url = source
                .finalUrl();
        int start = url
                .indexOf(rules
                        .antoraModulesPath());
        String commitRoot = url
                .substring(
                        0,
                        start
                );
        String commit = commitRoot
                .substring(
                        commitRoot
                                .lastIndexOf(
                                        '/',
                                        commitRoot
                                                .length() - 2
                                ) + 1,
                        commitRoot
                                .length() - 1
                );
        String rest = url
                .substring(start + rules
                        .antoraModulesPath()
                        .length());
        String module = rest
                .substring(
                        0,
                        rest
                                .indexOf('/')
                );
        String page = rest
                .substring(module
                        .length() + "/pages/"
                                .length());
        String minor = source
                .exactVersion()
                .replaceFirst(
                        "^(\\d+\\.\\d+).*$",
                        "$1"
                );
        String anchor = section
                .anchor();
        String readerUrl = rules
                .readerUrlRoot() + minor + "/" + ("ROOT"
                        .equals(module) ? "" : module + "/") + page
                                .substring(
                                        0,
                                        page
                                                .length() - ".adoc"
                                                        .length()
                                ) + ".html" + (anchor == null ? "" : "#" + anchor);
        Integer line = anchor == null ? null : asciidoc
                .anchorLines()
                .get(anchor);

        if (section
                .level() == 1 && asciidoc
                        .titleLine() > 0) {
            line = asciidoc
                    .titleLine();
        }

        String sourceUrl = rules
                .sourceViewRoot() + commit + "/" + url
                        .substring(commitRoot
                                .length()) + "?plain=1" + (line == null ? "" : "#L" + line);

        return new SectionCitation(
                readerUrl,
                sourceUrl
        );
    }

    // 블록 목록의 탭 그룹에 기본 변형 채우기
    private List<Block> withDefaultVariant(
            List<Block> blocks,
            ParserProfile profile
    ) {

        List<Block> resolved = new ArrayList<>();

        for (Block block : blocks) {
            if (block
                    .kind() != BlockKind.TAB_GROUP) {
                resolved
                        .add(block);

                continue;
            }

            TabGroup group = block
                    .tabGroup();
            DefaultVariantChoice choice = parserProfilePolicy
                    .resolveDefault(
                            profile,
                            group
                    );

            resolved
                    .add(new Block(
                            block
                                    .blockId(),
                            block
                                    .kind(),
                            block
                                    .ordinal(),
                            block
                                    .text(),
                            block
                                    .language(),
                            block
                                    .title(),
                            block
                                    .admonitionType(),
                            block
                                    .table(),
                            new TabGroup(
                                    group
                                            .groupLocator(),
                                    group
                                            .elementId(),
                                    group
                                            .groupType(),
                                    group
                                            .markedOrdinals(),
                                    group
                                            .variants()
                                            .get(choice
                                                    .ordinal())
                                            .variantKey(),
                                    choice
                                            .evidence(),
                                    group
                                            .variants()
                            ),
                            block
                                    .inlineCodes(),
                            block
                                    .links(),
                            block
                                    .sourcePointer()
                    ));
        }

        return resolved;
    }
}
