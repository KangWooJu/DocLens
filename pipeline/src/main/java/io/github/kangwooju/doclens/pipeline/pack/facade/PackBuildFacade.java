package io.github.kangwooju.doclens.pipeline.pack.facade;

import java.util.UUID;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.writer.PackWriter;
import io.github.kangwooju.doclens.pipeline.chunk.service.ChunkService;
import io.github.kangwooju.doclens.pipeline.global.transaction.TransactionExecutor;
import io.github.kangwooju.doclens.pipeline.pack.dto.ArtifactRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildState;
import io.github.kangwooju.doclens.pipeline.pack.dto.ExportTarget;
import io.github.kangwooju.doclens.pipeline.pack.dto.PackRecord;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceRead;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNotExportableException;
import io.github.kangwooju.doclens.pipeline.pack.service.BuildService;
import io.github.kangwooju.doclens.pipeline.pack.service.PackExportService;
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
    private final PackExportService packExportService;
    private final DocumentParseService documentParseService;
    private final ChunkService chunkService;
    private final TransactionExecutor transactionExecutor;

    /** ingest 뒤 봉인됐거나 이미 성공한 Build면 export까지, 같은 요청 재전송은 재개 */
    public BuildResult build(BuildRequest request) {

        BuildResult ingested = ingest(request);

        if (ingested
                .status() == BuildStatus.SEALED || ingested
                        .status() == BuildStatus.SUCCEEDED) {
            return export(ingested
                    .buildId());
        }

        return ingested;
    }

    /**
     * 봉인된 Build를 원본 없이 PostgreSQL만 읽어 Pack으로 게시
     * RUNNING은 그대로 반환, 보류, 실패, 없는 Build는 BuildNotExportableException, 성공한 Build는 게시 파일 확인과 정리 뒤 기존 결과
     */
    public BuildResult export(UUID buildId) {

        ExportTarget target = transactionExecutor
                .write(() -> buildService
                        .exportTarget(buildId));
        BuildRow build = target
                .build();

        switch (build
                .status()) {
            case RUNNING -> {
                return result(buildId);
            }
            case NEEDS_REVIEW, FAILED -> throw new BuildNotExportableException(
                    buildId
                            .toString(),
                    "status " + build
                            .status()
            );
            case SUCCEEDED -> {
                packExportService
                        .verifyRecorded(build);
                packExportService
                        .prune(build);

                return result(buildId);
            }
            case SEALED -> {
                return exportSealed(target);
            }
        }

        throw new IllegalStateException("unknown status " + build
                .status());
    }

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

    // 봉인된 Build의 Pack 채택 또는 작성, 게시, SUCCEEDED 기록, 정리
    private BuildResult exportSealed(ExportTarget target) {

        BuildRow build = target
                .build();

        if (target
                .chunkReceipt()
                .byteCount() > PackWriter.MAX_PACK_BYTES) {
            throw new BuildNotExportableException(
                    build
                            .buildId()
                            .toString(),
                    "CHUNK artifacts exceed the pack size limit"
            );
        }

        PackRecord adopted = packExportService
                .findPublished(target);
        PackRecord pack = adopted != null ? adopted : packExportService
                .publish(
                        build,
                        packExportService
                                .write(target)
                );

        transactionExecutor
                .write(() -> {
                    buildService
                            .markSucceeded(
                                    build
                                            .buildId(),
                                    pack,
                                    target
                                            .chunkReceipt()
                                            .itemCount()
                            );

                    return null;
                });

        ExportTarget succeeded = transactionExecutor
                .write(() -> buildService
                        .exportTarget(build
                                .buildId()));

        packExportService
                .prune(succeeded
                        .build());

        return result(build
                .buildId());
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

    // 시작 결과의 현재 Build 상태
    private BuildResult result(BuildState state) {

        return result(state
                .build()
                .buildId());
    }

    // 현재 Build 상태
    private BuildResult result(UUID buildId) {

        return transactionExecutor
                .write(() -> buildService
                        .result(buildId));
    }
}
