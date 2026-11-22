package io.github.kangwooju.doclens.pipeline.pack.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.pack.BuildFixtures;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.exception.ProcessingConfigMismatchException;
import io.github.kangwooju.doclens.pipeline.chunk.helper.ChunkPackHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.IdentifierHelper;
import io.github.kangwooju.doclens.pipeline.chunk.service.ChunkService;
import io.github.kangwooju.doclens.pipeline.parse.service.DocumentParseService;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;

@DisplayName("처리 설정 서비스")
class ProcessingConfigServiceTests {

    private final ProcessingConfigService service = BuildFixtures
            .processingConfigService();

    @Test
    @DisplayName("preimage에 실제 상수, 식별자 정규식, 등록 프로필, jsoup 버전이 들어감")
    void collectsRealConstants() {

        ProcessingConfig config = current();
        String preimage = config
                .preimageJson();

        assertTrue(preimage
                .contains("\"targetBytes\":" + ChunkPackHelper.TARGET_BYTES));
        assertTrue(preimage
                .contains(CanonicalJson
                        .write(IdentifierHelper.CODE_PATTERNS
                                .getFirst()
                                .pattern())));
        assertTrue(preimage
                .contains("\"profileId\":\"" + ParserProfileStore.SPRING_BOOT_REFERENCE
                        .profileId() + "\""));
        assertTrue(preimage
                .contains("\"jsoupVersion\":\"" + Jsoup.class
                        .getPackage()
                        .getImplementationVersion() + "\""));
        assertEquals(
                DocumentParseService.PARSER_POLICY_VERSION,
                config
                        .parserPolicyVersion()
        );
        assertEquals(
                ChunkService.CHUNKER_POLICY_VERSION,
                config
                        .chunkerPolicyVersion()
        );
    }

    @Test
    @DisplayName("요청 hash가 같으면 설정, 다르면 실제 hash를 담아 거부")
    void comparesRequestedHash() {

        ProcessingConfig config = current();

        assertEquals(
                config,
                service
                        .requireMatching(
                                config
                                        .hash(),
                                DocumentParseService.PARSER_POLICY_VERSION,
                                ChunkService.CHUNKER_POLICY_VERSION
                        )
        );

        ProcessingConfigMismatchException exception = assertThrows(
                ProcessingConfigMismatchException.class,
                () -> service
                        .requireMatching(
                                "sha256:" + "0"
                                        .repeat(64),
                                DocumentParseService.PARSER_POLICY_VERSION,
                                ChunkService.CHUNKER_POLICY_VERSION
                        )
        );

        assertEquals(
                config
                        .hash(),
                exception
                        .actualHash()
        );
    }

    // 현재 정책 버전의 처리 설정
    private ProcessingConfig current() {

        return service
                .current(
                        DocumentParseService.PARSER_POLICY_VERSION,
                        ChunkService.CHUNKER_POLICY_VERSION
                );
    }
}
