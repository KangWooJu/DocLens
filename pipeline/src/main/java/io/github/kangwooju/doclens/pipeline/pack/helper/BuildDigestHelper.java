package io.github.kangwooju.doclens.pipeline.pack.helper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;

/** Build 식별 hash와 원본 목록 digest, 단계 영수증 digest */
@Component
public class BuildDigestHelper {

    /** 작업 종류 */
    public static final String TASK_KIND = "DOCUMENT_BUILD";

    /** Pack inputSnapshotHash, run 입력과 범위와 정렬 documentId 목록 */
    public String inputSnapshotHash(BuildRequest request) {

        List<String> fields = new ArrayList<>();
        fields
                .add("pack-input-v1");
        fields
                .add(request
                        .runInputSnapshotHash());
        fields
                .add(request
                        .project());
        fields
                .add(request
                        .exactVersion());
        fields
                .add(request
                        .applicability());
        fields
                .add(request
                        .inputScopeKey());
        fields
                .add(Integer
                        .toString(request
                                .sources()
                                .size()));
        fields
                .addAll(request
                        .sources()
                        .stream()
                        .map(SourceEntry::documentId)
                        .sorted(CanonicalJson::compareCodePoints)
                        .toList());

        return IdHash
                .hash(fields
                        .toArray(String[]::new));
    }

    /** 작업 키, 작업 종류와 입력 스냅샷과 처리 설정과 모델 */
    public String jobKey(
            String inputSnapshotHash,
            String processingConfigHash
    ) {

        return IdHash
                .hash(
                        "job-v1",
                        TASK_KIND,
                        inputSnapshotHash,
                        processingConfigHash,
                        IdHash.NONE
                );
    }

    /**
     * 원본과 include 메타데이터 전체의 canonical hash, 같은 작업 키에 다른 원본이 오는지 판정
     * include가 없으면 원본 목록만의 값이라 include 이전 Build와 같음
     */
    public String sourceDigest(
            List<SourceEntry> sources,
            List<SourceEntry> includes
    ) {

        if (includes
                .isEmpty()) {
            return CanonicalJson
                    .sha256(CanonicalJson
                            .write(entries(sources)));
        }

        Map<String, Object> digest = new HashMap<>();
        digest
                .put(
                        "sources",
                        entries(sources)
                );
        digest
                .put(
                        "includes",
                        entries(includes)
                );

        return CanonicalJson
                .sha256(CanonicalJson
                        .write(digest));
    }

    /** documentId 순 [documentId, sha256] 목록의 canonical hash, 봉인과 Export가 같은 함수로 계산 */
    public String stageDigest(List<List<String>> documentHashes) {

        List<Object> rows = documentHashes
                .stream()
                .map(Object.class::cast)
                .toList();

        return CanonicalJson
                .sha256(CanonicalJson
                        .write(rows));
    }

    // documentId 순 원본마다 canonical 객체
    private static List<Object> entries(List<SourceEntry> sources) {

        return sources
                .stream()
                .sorted(Comparator
                        .comparing(
                                SourceEntry::documentId,
                                CanonicalJson::compareCodePoints
                        ))
                .map(BuildDigestHelper::sourceMap)
                .map(Object.class::cast)
                .toList();
    }

    // 원본 하나의 canonical 객체
    private static Map<String, Object> sourceMap(SourceEntry source) {

        Map<String, Object> map = new HashMap<>();
        map
                .put(
                        "documentId",
                        source
                                .documentId()
                );
        map
                .put(
                        "documentType",
                        source
                                .documentType()
                                .name()
                );
        map
                .put(
                        "canonicalUrl",
                        source
                                .canonicalUrl()
                );
        map
                .put(
                        "originalUrl",
                        source
                                .originalUrl()
                );
        map
                .put(
                        "finalUrl",
                        source
                                .finalUrl()
                );
        map
                .put(
                        "sourceSnapshotId",
                        source
                                .sourceSnapshotId()
                );
        map
                .put(
                        "rawSha256",
                        source
                                .rawSha256()
                );
        map
                .put(
                        "rawObjectKey",
                        source
                                .rawObjectKey()
                );

        return map;
    }
}
