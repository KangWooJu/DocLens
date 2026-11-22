package io.github.kangwooju.doclens.pipeline.pack.facade;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.pipeline.chunk.service.ChunkService;
import io.github.kangwooju.doclens.pipeline.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.pipeline.pack.dto.ArtifactRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildState;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceRead;
import io.github.kangwooju.doclens.pipeline.pack.service.BuildService;
import io.github.kangwooju.doclens.pipeline.pack.service.ProcessingConfigService;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.pipeline.pack.type.SourceReadOutcome;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.exception.DocumentHoldException;
import io.github.kangwooju.doclens.pipeline.parse.service.DocumentParseService;
import lombok.RequiredArgsConstructor;

/**
 * 원본에서 Pack까지의 순서와 트랜잭션 범위
 * 같은 요청을 다시 보내면 산출물이 없는 문서부터 이어 감
 */
@Component
@RequiredArgsConstructor
public class PackBuildFacade {

    private final ProcessingConfigService processingConfigService;
    private final BuildService buildService;
    private final DocumentParseService documentParseService;
    private final ChunkService chunkService;
    private final TransactionExecutor transactionExecutor;

    /**
     * Build 시작 또는 재개, 산출물 없는 문서만 PARSE와 CHUNK, 모두 있으면 봉인
     * 처리 설정 hash가 다르면 DB에 쓰기 전에 ProcessingConfigMismatchException
     */
    public BuildResult ingest(BuildRequest request) {

        ProcessingConfig config = processingConfigService
                .requireMatching(
                        request
                                .processingConfigHash(),
                        DocumentParseService.PARSER_POLICY_VERSION,
                        ChunkService.CHUNKER_POLICY_VERSION
                );
        BuildState state = transactionExecutor
                .write(() -> buildService
                        .start(
                                request,
                                config
                        ));

        if (state
                .build()
                .status() != BuildStatus.RUNNING) {
            return result(state);
        }

        for (SourceEntry source : state
                .pending()) {
            SourceRead read = buildService
                    .readSource(
                            state
                                    .build(),
                            source
                    );

            if (read
                    .outcome() == SourceReadOutcome.MISSING) {
                continue;
            }

            if (read
                    .outcome() == SourceReadOutcome.HASH_MISMATCH) {
                transactionExecutor
                        .write(() -> {
                            buildService
                                    .fail(
                                            state
                                                    .build()
                                                    .buildId(),
                                            "raw bytes do not match rawSha256 for " + source
                                                    .documentId()
                                    );

                            return null;
                        });

                return result(state);
            }

            ArtifactRow artifact = artifact(read);

            transactionExecutor
                    .write(() -> {
                        buildService
                                .saveArtifact(
                                        state
                                                .build()
                                                .buildId(),
                                        source
                                                .documentId(),
                                        artifact
                                );

                        return null;
                    });
        }

        transactionExecutor
                .write(() -> buildService
                        .seal(state
                                .build()
                                .buildId()));

        return result(state);
    }

    // 원본 하나의 PARSE와 CHUNK 산출물, 보류면 보류 행, CPU 작업이라 트랜잭션 밖
    private ArtifactRow artifact(SourceRead read) {

        try {
            StructuredDocument structured = documentParseService
                    .parse(read
                            .document());
            PackDocument packDocument = chunkService
                    .chunk(structured);

            return buildService
                    .prepareArtifact(
                            structured,
                            packDocument
                    );
        } catch (DocumentHoldException exception) {
            return buildService
                    .prepareHeld(exception
                            .reason());
        }
    }

    // 현재 Build 상태
    private BuildResult result(BuildState state) {

        return transactionExecutor
                .write(() -> buildService
                        .result(state
                                .build()
                                .buildId()));
    }
}
