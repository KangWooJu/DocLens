package io.github.kangwooju.doclens.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;

/**
 * 합성 fixture 원본으로 만든 collector readyRun 묶음, batch 하나
 * 시험은 manifest 내용을 바꾼 뒤 write로 다시 묶어 hash가 맞는 잘못된 묶음을 만듦
 */
public final class CollectedRunFixtures {

    /** 회차 ID */
    public static final String RUN_ID = "11111111-1111-4111-8111-111111111111";

    /** batch ID */
    public static final String BATCH_ID = "22222222-2222-4222-8222-222222222222";

    /** snapshot ID */
    public static final String SNAPSHOT_ID = "33333333-3333-4333-8333-333333333333";

    /** project */
    public static final String PROJECT = "spring-boot";

    /** 합성 fixture 버전 */
    public static final String VERSION = "4.1.2";

    /** projection 범위 key */
    public static final String INPUT_SCOPE_KEY = "sha256:" + "a"
            .repeat(64);

    private static final String TIMESTAMP = "2026-11-24T00:00:00Z";

    // 인스턴스 생성 금지
    private CollectedRunFixtures() {
    }

    /** 문서 하나의 fixture 이름, contracts 문서 유형, 필수 여부, 수집 성공 여부 */
    public record Document(
            String fixture,
            String documentType,
            boolean required,
            boolean succeeded
    ) {
    }

    /** 바꿀 수 있는 manifest 내용, objectRef 필드는 write가 채움 */
    public record Parts(
            List<Map<String, Object>> targets,
            List<Map<String, Object>> outcomes,
            Map<String, Object> discovery,
            Map<String, Object> envelope,
            Map<String, Object> readyRun,
            Map<String, byte[]> raws
    ) {
    }

    /** object key별 bytes와 readyRun objectRef */
    public record Bundle(
            ObjectRef readyRun,
            Map<String, byte[]> objects
    ) {
    }

    /** 실패 없는 Reference 문서 두 개, documentId 순서는 fixture 순서와 무관 */
    public static List<Document> succeededReferences() {

        return PackRequestFixtures.FIXTURES
                .stream()
                .map(fixture -> new Document(
                        fixture,
                        "REFERENCE",
                        true,
                        true
                ))
                .toList();
    }

    /** 실패 없는 Reference 두 문서의 묶음 */
    public static Bundle bundle() {

        return write(parts(succeededReferences()));
    }

    /** 문서 목록의 manifest 내용 */
    public static Parts parts(List<Document> documents) {

        List<Map<String, Object>> targets = new ArrayList<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        Map<String, byte[]> raws = new LinkedHashMap<>();
        List<Object> requiredInputs = new ArrayList<>();
        List<Object> optionalInputs = new ArrayList<>();
        int successCount = 0;

        for (Document document : documents) {
            Map<String, Object> input = input(document);
            String url = PackRequestFixtures.BASE_URL + document
                    .fixture();
            String documentId = IdHash
                    .hash(
                            "document-v1",
                            PROJECT,
                            VERSION,
                            "html",
                            document
                                    .documentType(),
                            url
                    );
            Map<String, Object> target = new LinkedHashMap<>();
            target
                    .put(
                            "documentId",
                            documentId
                    );
            target
                    .put(
                            "url",
                            url
                    );
            target
                    .put(
                            "input",
                            input
                    );
            targets
                    .add(target);

            if (document
                    .required()) {
                requiredInputs
                        .add(input);
            } else {
                optionalInputs
                        .add(input);
            }

            byte[] raw = PackRequestFixtures
                    .bytes(document
                            .fixture());
            String rawKey = "raw/" + PROJECT + "/" + VERSION + "/" + sha256(raw)
                    .substring(7) + ".html";

            if (document
                    .succeeded()) {
                raws
                        .put(
                                rawKey,
                                raw
                        );
                successCount++;
            }

            outcomes
                    .add(outcome(
                            target,
                            document
                                    .succeeded() ? ref(
                                            rawKey,
                                            raw
                                    ) : null
                    ));
        }

        Map<String, Object> batch = new LinkedHashMap<>();
        batch
                .put(
                        "batchId",
                        BATCH_ID
                );
        batch
                .put(
                        "targetCount",
                        documents
                                .size()
                );

        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery
                .put(
                        "schemaVersion",
                        1
                );
        discovery
                .put(
                        "runId",
                        RUN_ID
                );
        discovery
                .put(
                        "state",
                        "SEALED"
                );
        discovery
                .put(
                        "discoveryMethod",
                        "NAV_CLOSED"
                );
        discovery
                .put(
                        "requiredInputs",
                        requiredInputs
                );
        discovery
                .put(
                        "optionalInputs",
                        optionalInputs
                );
        discovery
                .put(
                        "targetCount",
                        documents
                                .size()
                );
        discovery
                .put(
                        "batches",
                        new ArrayList<>(List
                                .of(batch))
                );
        discovery
                .put(
                        "snapshotId",
                        SNAPSHOT_ID
                );
        discovery
                .put(
                        "projections",
                        List
                                .of(projection())
                );

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope
                .put(
                        "schemaVersion",
                        1
                );
        envelope
                .put(
                        "runId",
                        RUN_ID
                );
        envelope
                .put(
                        "batchId",
                        BATCH_ID
                );
        envelope
                .put(
                        "targetCount",
                        documents
                                .size()
                );
        envelope
                .put(
                        "successCount",
                        successCount
                );
        envelope
                .put(
                        "failureCount",
                        documents
                                .size() - successCount
                );
        envelope
                .put(
                        "sealedAt",
                        TIMESTAMP
                );
        envelope
                .put(
                        "snapshotId",
                        SNAPSHOT_ID
                );

        Map<String, Object> readyRun = new LinkedHashMap<>();
        readyRun
                .put(
                        "schemaVersion",
                        1
                );
        readyRun
                .put(
                        "readyRunId",
                        "44444444-4444-4444-8444-444444444444"
                );
        readyRun
                .put(
                        "readinessSequence",
                        1
                );
        readyRun
                .put(
                        "runId",
                        RUN_ID
                );
        readyRun
                .put(
                        "snapshotId",
                        SNAPSHOT_ID
                );
        readyRun
                .put(
                        "supplements",
                        List
                                .of()
                );
        readyRun
                .put(
                        "projections",
                        List
                                .of(projection())
                );
        readyRun
                .put(
                        "createdAt",
                        TIMESTAMP
                );

        return new Parts(
                targets,
                outcomes,
                discovery,
                envelope,
                readyRun,
                raws
        );
    }

    /** manifest 내용을 collector key 규칙대로 bytes로 쓰고 아래에서 위로 objectRef를 채움 */
    @SuppressWarnings("unchecked")
    public static Bundle write(Parts parts) {

        Map<String, byte[]> objects = new LinkedHashMap<>(parts
                .raws());
        String manifests = "manifests/" + RUN_ID + "/" + BATCH_ID + "/";
        Map<String, Object> runTargets = put(
                objects,
                "discovery/" + RUN_ID + "/targets.jsonl",
                jsonl(parts
                        .targets())
        );
        Map<String, Object> batchTargets = put(
                objects,
                manifests + "targets.jsonl",
                jsonl(parts
                        .targets())
        );
        Map<String, Object> discovery = parts
                .discovery();
        discovery
                .put(
                        "targetList",
                        runTargets
                );

        for (Object batch : (List<Object>) discovery
                .get("batches")) {
            ((Map<String, Object>) batch)
                    .put(
                            "targetList",
                            batchTargets
                    );
        }

        Map<String, Object> discoveryRef = put(
                objects,
                "discovery/" + RUN_ID + "/discovery.json",
                json(discovery)
        );
        Map<String, Object> outcomesRef = put(
                objects,
                manifests + "outcomes.jsonl",
                jsonl(parts
                        .outcomes())
        );
        Map<String, Object> envelope = parts
                .envelope();
        envelope
                .put(
                        "discovery",
                        discoveryRef
                );
        envelope
                .put(
                        "targetList",
                        batchTargets
                );
        envelope
                .put(
                        "jsonl",
                        outcomesRef
                );

        Map<String, Object> envelopeRef = put(
                objects,
                manifests + "envelope.json",
                json(envelope)
        );
        Map<String, Object> readyRun = parts
                .readyRun();
        readyRun
                .put(
                        "discovery",
                        discoveryRef
                );
        readyRun
                .put(
                        "envelopes",
                        List
                                .of(envelopeRef)
                );

        byte[] readyRunBytes = json(readyRun);
        String readyRunKey = "manifests/" + RUN_ID + "/ready-run.json";
        objects
                .put(
                        readyRunKey,
                        readyRunBytes
                );

        return new Bundle(
                new ObjectRef(
                        readyRunKey,
                        sha256(readyRunBytes),
                        readyRunBytes.length
                ),
                objects
        );
    }

    /** bytes의 sha256: 접두 hex */
    public static String sha256(byte[] bytes) {

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

    // 문서 하나의 contracts input
    private static Map<String, Object> input(Document document) {

        Map<String, Object> input = new LinkedHashMap<>();
        input
                .put(
                        "sourceId",
                        PROJECT + "-" + VERSION + "-" + document
                                .documentType()
                );
        input
                .put(
                        "project",
                        PROJECT
                );
        input
                .put(
                        "exactVersion",
                        VERSION
                );
        input
                .put(
                        "sourceType",
                        "html"
                );
        input
                .put(
                        "documentType",
                        document
                                .documentType()
                );
        input
                .put(
                        "required",
                        document
                                .required()
                );
        input
                .put(
                        "applicability",
                        "EXACT_RELEASE"
                );

        return input;
    }

    // 대상 하나의 outcome, raw가 없으면 FAILED
    private static Map<String, Object> outcome(
            Map<String, Object> target,
            Map<String, Object> raw
    ) {

        Map<String, Object> outcome = new LinkedHashMap<>(target);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence
                .put(
                        "level",
                        "UNVERIFIED"
                );
        evidence
                .put(
                        "sourceUrl",
                        target
                                .get("url")
                );
        evidence
                .put(
                        "observedAt",
                        TIMESTAMP
                );
        outcome
                .put(
                        "runId",
                        RUN_ID
                );
        outcome
                .put(
                        "batchId",
                        BATCH_ID
                );
        outcome
                .put(
                        "snapshotId",
                        SNAPSHOT_ID
                );
        outcome
                .put(
                        "finalUrl",
                        raw == null ? null : target
                                .get("url")
                );

        if (raw == null) {
            evidence
                    .put(
                            "method",
                            "OBSERVATION_ONLY"
                    );
            evidence
                    .put(
                            "contentHash",
                            sha256(new byte[0])
                    );
            outcome
                    .put(
                            "outcome",
                            "FAILED"
                    );
            outcome
                    .put(
                            "errorCode",
                            "HTTP_RETRY_EXHAUSTED"
                    );
        } else {
            evidence
                    .put(
                            "method",
                            "PAGE_MARKER"
                    );
            evidence
                    .put(
                            "exactVersion",
                            VERSION
                    );
            evidence
                    .put(
                            "contentHash",
                            raw
                                    .get("contentHash")
                    );
            outcome
                    .put(
                            "outcome",
                            "SUCCESS"
                    );
            outcome
                    .put(
                            "raw",
                            raw
                    );
        }

        outcome
                .put(
                        "versionEvidence",
                        evidence
                );

        return outcome;
    }

    // readyRun과 discovery의 projection 하나
    private static Map<String, Object> projection() {

        Map<String, Object> scope = new LinkedHashMap<>();
        scope
                .put(
                        "project",
                        PROJECT
                );
        scope
                .put(
                        "exactVersion",
                        VERSION
                );
        scope
                .put(
                        "applicability",
                        "EXACT_RELEASE"
                );

        Map<String, Object> projection = new LinkedHashMap<>();
        projection
                .put(
                        "packScope",
                        scope
                );
        projection
                .put(
                        "inputScopeKey",
                        INPUT_SCOPE_KEY
                );
        projection
                .put(
                        "observationSequence",
                        1
                );

        return projection;
    }

    // bytes를 key에 두고 objectRef JSON 값
    private static Map<String, Object> put(
            Map<String, byte[]> objects,
            String key,
            byte[] bytes
    ) {

        objects
                .put(
                        key,
                        bytes
                );

        return ref(
                key,
                bytes
        );
    }

    // objectRef JSON 값
    private static Map<String, Object> ref(
            String key,
            byte[] bytes
    ) {

        Map<String, Object> ref = new LinkedHashMap<>();
        ref
                .put(
                        "objectKey",
                        key
                );
        ref
                .put(
                        "contentHash",
                        sha256(bytes)
                );
        ref
                .put(
                        "sizeBytes",
                        bytes.length
                );

        return ref;
    }

    // canonical JSON 한 줄
    private static byte[] json(Map<String, Object> value) {

        return (CanonicalJson
                .write(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    // canonical JSON 줄 목록
    private static byte[] jsonl(List<Map<String, Object>> lines) {

        StringBuilder out = new StringBuilder();

        for (Map<String, Object> line : lines) {
            out
                    .append(CanonicalJson
                            .write(line))
                    .append('\n');
        }

        return out
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }
}
