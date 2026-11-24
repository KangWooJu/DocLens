package io.github.kangwooju.doclens.pipeline.pack.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;
import io.github.kangwooju.doclens.pipeline.pack.converter.PackDocumentConverter;
import io.github.kangwooju.doclens.pipeline.pack.dto.ArtifactRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildResult;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRow;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildState;
import io.github.kangwooju.doclens.pipeline.pack.dto.DocumentHashes;
import io.github.kangwooju.doclens.pipeline.pack.dto.ExportTarget;
import io.github.kangwooju.doclens.pipeline.pack.dto.NewBuild;
import io.github.kangwooju.doclens.pipeline.pack.dto.PackRecord;
import io.github.kangwooju.doclens.pipeline.pack.dto.ProcessingConfig;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceRead;
import io.github.kangwooju.doclens.pipeline.pack.dto.StageDigest;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildInputConflictException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNotExportableException;
import io.github.kangwooju.doclens.pipeline.pack.exception.StageNondeterminismException;
import io.github.kangwooju.doclens.pipeline.pack.helper.BuildDigestHelper;
import io.github.kangwooju.doclens.pipeline.pack.policy.BuildRequestPolicy;
import io.github.kangwooju.doclens.pipeline.pack.policy.ProcessingConfigPolicy;
import io.github.kangwooju.doclens.pipeline.pack.repository.BuildRepository;
import io.github.kangwooju.doclens.pipeline.pack.type.ArtifactOutcome;
import io.github.kangwooju.doclens.pipeline.pack.type.BuildStatus;
import io.github.kangwooju.doclens.pipeline.pack.type.SourceReadOutcome;
import io.github.kangwooju.doclens.pipeline.parse.converter.StructuredDocumentConverter;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.pipeline.parse.dto.StructuredDocument;
import io.github.kangwooju.doclens.pipeline.parse.type.HoldReason;
import lombok.RequiredArgsConstructor;

/**
 * Build 시작과 재개, 원본 읽기, 산출물 준비와 저장, 봉인, 실패 기록
 * 트랜잭션 범위는 Facade가 정하고 이 서비스는 한 범위 안의 일만 함
 */
@Service
@RequiredArgsConstructor
public class BuildService {

    private final BuildRepository buildRepository;
    private final BuildRequestPolicy buildRequestPolicy;
    private final BuildDigestHelper buildDigestHelper;
    private final ObjectStore objectStore;
    private final StructuredDocumentConverter structuredDocumentConverter;
    private final PackDocumentConverter packDocumentConverter;

    /** 요청 검사 뒤 Build를 만들거나 기존 Build를 찾음, 같은 작업 키에 다른 원본이면 BuildInputConflictException */
    public BuildState start(
            BuildRequest request,
            ProcessingConfig config
    ) {

        buildRequestPolicy
                .requireValid(request);

        String inputSnapshotHash = buildDigestHelper
                .inputSnapshotHash(request);
        String jobKey = buildDigestHelper
                .jobKey(
                        inputSnapshotHash,
                        config
                                .hash()
                );
        String sourceDigest = buildDigestHelper
                .sourceDigest(
                        request
                                .sources(),
                        request
                                .includes()
                );
        Optional<UUID> created = buildRepository
                .insertBuild(new NewBuild(
                        UUID
                                .randomUUID(),
                        jobKey,
                        request
                                .project() + "-" + request
                                        .exactVersion(),
                        ProcessingConfigPolicy.SOURCE_ATTRIBUTION,
                        ProcessingConfigPolicy.CREATED_BY,
                        request
                                .project(),
                        request
                                .exactVersion(),
                        request
                                .applicability(),
                        request
                                .runInputSnapshotHash(),
                        request
                                .inputScopeKey(),
                        inputSnapshotHash,
                        config
                                .hash(),
                        config
                                .preimageJson(),
                        config
                                .parserPolicyVersion(),
                        config
                                .chunkerPolicyVersion(),
                        sourceDigest,
                        request
                                .sources()
                                .size()
                ));

        created
                .ifPresent(buildId -> buildRepository
                        .insertSources(
                                buildId,
                                request
                                        .sources()
                        ));

        BuildRow build = buildRepository
                .findByJobKey(jobKey)
                .orElseThrow();

        if (!build
                .sourceDigest()
                .equals(sourceDigest)) {
            throw new BuildInputConflictException(jobKey);
        }

        return new BuildState(
                build,
                build
                        .status() == BuildStatus.RUNNING ? buildRepository
                                .pendingSources(build
                                        .buildId()) : List
                                                .of(),
                buildRepository
                        .holds(build
                                .buildId())
        );
    }

    /** 객체 저장소의 원본 bytes를 읽어 원본 문서로, 객체 없음과 hash 불일치는 결과로 알림 */
    public SourceRead readSource(
            BuildRow build,
            SourceEntry source
    ) {

        Optional<byte[]> bytes = objectStore
                .read(source
                        .rawObjectKey());

        if (bytes
                .isEmpty()) {
            return new SourceRead(
                    SourceReadOutcome.MISSING,
                    null
            );
        }

        if (!sha256(bytes
                .get())
                .equals(source
                        .rawSha256())) {
            return new SourceRead(
                    SourceReadOutcome.HASH_MISMATCH,
                    null
            );
        }

        return new SourceRead(
                SourceReadOutcome.READ,
                new SourceDocument(
                        source
                                .documentId(),
                        build
                                .project(),
                        build
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
                        bytes
                                .get()
                )
        );
    }

    /** 구조 산출물과 Pack 문서의 canonical JSON과 hash, JSON 왕복이 같지 않으면 IllegalStateException */
    public ArtifactRow prepareArtifact(
            StructuredDocument structured,
            PackDocument packDocument
    ) {

        String structuredJson = roundTrip(
                structured,
                structuredDocumentConverter::toJson,
                structuredDocumentConverter::fromJson
        );
        String packDocumentJson = roundTrip(
                packDocument,
                packDocumentConverter::toJson,
                packDocumentConverter::fromJson
        );

        return new ArtifactRow(
                ArtifactOutcome.DONE,
                null,
                structuredJson,
                CanonicalJson
                        .sha256(structuredJson),
                packDocumentJson,
                CanonicalJson
                        .sha256(packDocumentJson)
        );
    }

    /** 보류 문서의 산출물 행 */
    public ArtifactRow prepareHeld(HoldReason reason) {

        return new ArtifactRow(
                ArtifactOutcome.HELD,
                reason,
                null,
                null,
                null,
                null
        );
    }

    /** 산출물 저장, 이미 있는데 결과나 hash가 다르면 StageNondeterminismException */
    public void saveArtifact(
            UUID buildId,
            String documentId,
            ArtifactRow artifact
    ) {

        if (buildRepository
                .insertArtifact(
                        buildId,
                        documentId,
                        artifact
                )) {
            return;
        }

        ArtifactRow stored = buildRepository
                .findArtifactHashes(
                        buildId,
                        documentId
                )
                .orElseThrow();
        boolean same = stored
                .outcome() == artifact
                        .outcome() && stored
                                .holdReason() == artifact
                                        .holdReason() && Objects
                                                .equals(
                                                        stored
                                                                .structuredSha256(),
                                                        artifact
                                                                .structuredSha256()
                                                ) && Objects
                                                        .equals(
                                                                stored
                                                                        .packDocumentSha256(),
                                                                artifact
                                                                        .packDocumentSha256()
                                                        );

        if (!same) {
            throw new StageNondeterminismException(
                    buildId
                            .toString(),
                    documentId
            );
        }
    }

    /** 모든 원본에 산출물이 있으면 봉인, SEALED면 같은 범위에서 PARSE와 CHUNK 영수증, 아니면 현재 상태 */
    public BuildStatus seal(UUID buildId) {

        Optional<BuildStatus> sealed = buildRepository
                .seal(buildId);

        if (sealed
                .isEmpty()) {
            return buildRepository
                    .findById(buildId)
                    .orElseThrow()
                    .status();
        }

        if (sealed
                .get() == BuildStatus.SEALED) {
            List<DocumentHashes> hashes = buildRepository
                    .doneHashes(buildId);

            buildRepository
                    .insertReceipt(
                            buildId,
                            "PARSE",
                            stageDigest(
                                    hashes,
                                    DocumentHashes::structuredSha256,
                                    DocumentHashes::structuredBytes
                            )
                    );
            buildRepository
                    .insertReceipt(
                            buildId,
                            "CHUNK",
                            stageDigest(
                                    hashes,
                                    DocumentHashes::packDocumentSha256,
                                    DocumentHashes::packDocumentBytes
                            )
                    );
        }

        return sealed
                .get();
    }

    /** RUNNING Build를 사유와 함께 실패로 끝냄 */
    public void fail(
            UUID buildId,
            String reason
    ) {

        buildRepository
                .fail(
                        buildId,
                        reason
                );
    }

    /** Export 대상과 Pack 메타 원본 목록, CHUNK 영수증, 없는 Build면 BuildNotExportableException */
    public ExportTarget exportTarget(UUID buildId) {

        BuildRow build = buildRepository
                .findById(buildId)
                .orElseThrow(() -> new BuildNotExportableException(
                        buildId
                                .toString(),
                        "no such build"
                ));

        return new ExportTarget(
                build,
                buildRepository
                        .sourceSnapshotIds(buildId),
                buildRepository
                        .receipt(
                                buildId,
                                "CHUNK"
                        )
                        .orElse(null)
        );
    }

    /** SEALED Build를 게시한 Pack과 함께 SUCCEEDED로, 바뀌면 같은 범위에서 EXPORT 영수증 */
    public void markSucceeded(
            UUID buildId,
            PackRecord pack,
            int documentCount
    ) {

        if (buildRepository
                .markSucceeded(
                        buildId,
                        pack
                )) {
            buildRepository
                    .insertReceipt(
                            buildId,
                            "EXPORT",
                            new StageDigest(
                                    pack
                                            .logicalContentSha256(),
                                    documentCount,
                                    pack
                                            .sizeBytes()
                            )
                    );
        }
    }

    /** 호출자에게 돌려줄 현재 Build 상태 */
    public BuildResult result(UUID buildId) {

        BuildRow build = buildRepository
                .findById(buildId)
                .orElseThrow();

        return new BuildResult(
                build
                        .buildId(),
                build
                        .status(),
                build
                        .processingConfigHash(),
                buildRepository
                        .holds(buildId),
                build
                        .failureReason(),
                build
                        .pack()
        );
    }

    // canonical JSON과 그 왕복 확인
    private static <T> String roundTrip(
            T value,
            Function<T, String> toJson,
            Function<String, T> fromJson
    ) {

        String json = toJson
                .apply(value);

        if (!fromJson
                .apply(json)
                .equals(value)) {
            throw new IllegalStateException("artifact does not survive a canonical JSON round trip");
        }

        return json;
    }

    // DONE 산출물 목록의 단계 영수증
    private StageDigest stageDigest(
            List<DocumentHashes> hashes,
            Function<DocumentHashes, String> sha,
            ToLongFunction<DocumentHashes> bytes
    ) {

        return new StageDigest(
                buildDigestHelper
                        .stageDigest(hashes
                                .stream()
                                .map(row -> List
                                        .of(
                                                row
                                                        .documentId(),
                                                sha
                                                        .apply(row)
                                        ))
                                .toList()),
                hashes
                        .size(),
                hashes
                        .stream()
                        .mapToLong(bytes)
                        .sum()
        );
    }

    // bytes의 sha256: 접두 hex
    private static String sha256(byte[] bytes) {

        try {
            return "sha256:" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
