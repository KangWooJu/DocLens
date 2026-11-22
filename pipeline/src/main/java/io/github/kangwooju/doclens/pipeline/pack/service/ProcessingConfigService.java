package io.github.kangwooju.doclens.pipeline.pack.service;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfigInputs;
import io.github.kangwooju.doclens.pipeline.pack.exception.ProcessingConfigMismatchException;
import io.github.kangwooju.doclens.pipeline.pack.policy.ProcessingConfigPolicy;
import io.github.kangwooju.doclens.pipeline.chunk.helper.BlockRenderHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.ChunkPackHelper;
import io.github.kangwooju.doclens.pipeline.chunk.helper.IdentifierHelper;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.policy.ParserProfilePolicy;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;
import lombok.RequiredArgsConstructor;

/**
 * 지금 JVM이 실제로 적용할 처리 설정과 요청 hash 대조
 * 정책 버전은 Service끼리 의존하지 않도록 호출자가 넘김
 */
@Service
@RequiredArgsConstructor
public class ProcessingConfigService {

    private final ParserProfileStore parserProfileStore;
    private final ProcessingConfigPolicy processingConfigPolicy;

    /** 실제 상수, 등록 프로필, jsoup 버전으로 만든 처리 설정 */
    public ProcessingConfig current(
            String parserPolicyVersion,
            String chunkerPolicyVersion
    ) {

        return processingConfigPolicy
                .create(inputs(
                        parserPolicyVersion,
                        chunkerPolicyVersion
                ));
    }

    /** 요청 hash와 같으면 실제 설정, 다르면 ProcessingConfigMismatchException */
    public ProcessingConfig requireMatching(
            String requestedHash,
            String parserPolicyVersion,
            String chunkerPolicyVersion
    ) {

        ProcessingConfig config = current(
                parserPolicyVersion,
                chunkerPolicyVersion
        );

        if (!processingConfigPolicy
                .matches(
                        requestedHash,
                        config
                )) {
            throw new ProcessingConfigMismatchException(
                    requestedHash,
                    config
                            .hash()
            );
        }

        return config;
    }

    // preimage 입력값을 실제 상수에서 채움
    private ProcessingConfigInputs inputs(
            String parserPolicyVersion,
            String chunkerPolicyVersion
    ) {

        return new ProcessingConfigInputs(
                parserPolicyVersion,
                chunkerPolicyVersion,
                jsoupVersion(),
                StructuredDocument.SCHEMA_VERSION,
                ParserProfilePolicy.MAX_CODE_BYTES,
                ChunkPackHelper.TARGET_BYTES,
                ChunkPackHelper.MAX_BYTES,
                ChunkPackHelper.MAX_BLOCKS,
                ChunkPackHelper.OVERLAP_MAX_BYTES,
                ChunkPackHelper.HEADER_REPEAT_MAX_BYTES,
                BlockRenderHelper.SEPARATOR,
                IdentifierHelper.CODE_PATTERNS
                        .stream()
                        .map(Pattern::pattern)
                        .toList(),
                IdentifierHelper.MAX_LENGTH,
                ProcessingConfigPolicy.SOURCE_ATTRIBUTION,
                ProcessingConfigPolicy.CREATED_BY,
                parserProfileStore
                        .profiles()
        );
    }

    // jar manifest의 jsoup 버전, 없으면 시작 실패
    private static String jsoupVersion() {

        String version = Jsoup.class
                .getPackage()
                .getImplementationVersion();

        if (version == null) {
            throw new IllegalStateException("jsoup Implementation-Version is missing from its manifest");
        }

        return version;
    }
}
