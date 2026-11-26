package io.github.kangwooju.doclens.pipeline.pack;

import java.util.List;

import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.pipeline.pack.policy.ProcessingConfigPolicy;
import io.github.kangwooju.doclens.pipeline.pack.service.ProcessingConfigService;
import io.github.kangwooju.doclens.pipeline.chunk.converter.ChunkJsonConverter;
import io.github.kangwooju.doclens.pipeline.chunk.helper.BlockRenderHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.ChunkPackHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.IdentifierHelper;
import io.github.kangwooju.doclens.pipeline.chunk.service.ChunkService;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.service.DocumentParseService;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;

/** Build 시험용 fixture 이름, Spring 없이 만든 서비스, 파싱과 청크 결과 */
public final class BuildFixtures {

    /** AsciiDoc fixture 쪽, 같은 fixture 트리의 include 전부와 함께 기본 등록 프로필로 파싱 */
    public static final String ASCIIDOC_FIXTURE = "sample.adoc";

    /** 골든 시험과 왕복 시험이 쓰는 fixture */
    public static final List<String> FIXTURES = List
            .of(
                    "reference.html",
                    "f16-sections-tabs-table-links.html",
                    ASCIIDOC_FIXTURE
            );

    // 인스턴스 생성 금지
    private BuildFixtures() {
    }

    /** 기본 등록 프로필로 만든 처리 설정 서비스 */
    public static ProcessingConfigService processingConfigService() {

        return new ProcessingConfigService(
                new ParserProfileStore(),
                new ProcessingConfigPolicy()
        );
    }

    /** fixture 하나의 구조 산출물 */
    public static StructuredDocument structured(String fixture) {

        if (fixture
                .equals(ASCIIDOC_FIXTURE)) {
            return ParseFixtures
                    .service(ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE)
                    .parse(
                            ParseFixtures
                                    .asciidocPage(fixture),
                            ParseFixtures
                                    .asciidocIncludes()
                    );
        }

        return ParseFixtures
                .service()
                .parse(ParseFixtures
                        .source(ParseFixtures
                                .html(fixture)));
    }

    /** fixture 하나의 Pack 문서 */
    public static PackDocument packDocument(String fixture) {

        return chunkService()
                .chunk(structured(fixture));
    }

    /** Spring 없이 만든 청크 서비스 */
    public static ChunkService chunkService() {

        return new ChunkService(
                new BlockRenderHelper(),
                new ChunkPackHelper(),
                new IdentifierHelper(),
                new ChunkJsonConverter()
        );
    }

    /** 현재 정책 버전으로 만든 처리 설정 hash */
    public static String currentHash() {

        return processingConfigService()
                .current(
                        DocumentParseService.PARSER_POLICY_VERSION,
                        ChunkService.CHUNKER_POLICY_VERSION
                )
                .hash();
    }
}
