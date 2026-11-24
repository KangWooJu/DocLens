package io.github.kangwooju.doclens.pipeline.collected.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunIncompleteException;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunInvalidException;
import io.github.kangwooju.doclens.pipeline.global.id.IdHash;
import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** collector readyRun 하나를 pipeline Build 요청으로, 참조 manifest의 hash, 크기, 집합, 개수를 모두 검증 */
@Service
@RequiredArgsConstructor
public class CollectedRunService {

    /** run 입력 snapshot hash의 첫 필드 */
    public static final String INPUT_SNAPSHOT_DOMAIN = "input-snapshot-v1";

    // 원본 없이 실패한 문서의 raw 자리 값
    private static final String NO_RAW = "none";

    private static final Comparator<String> CODE_POINT_ORDER = CanonicalJson::compareCodePoints;

    private final ObjectStore objectStore;
    private final JsonMapper jsonMapper;

    /** projection마다 BuildRequest 하나, 필수 문서 실패가 있으면 CollectedRunIncompleteException */
    public List<BuildRequest> requests(
            ObjectRef readyRun,
            String processingConfigHash
    ) {

        JsonNode ready = json(readyRun);
        ObjectRef discoveryRef = ref(
                ready,
                "discovery"
        );
        JsonNode discovery = json(discoveryRef);

        requireSame(
                ready,
                discovery,
                "runId"
        );
        requireSame(
                ready,
                discovery,
                "snapshotId"
        );
        requireSame(
                ready,
                discovery,
                "projections"
        );

        Map<String, JsonNode> batches = batches(discovery);
        Map<String, String> envelopeHashes = new TreeMap<>(CODE_POINT_ORDER);
        List<JsonNode> outcomes = new ArrayList<>();

        for (JsonNode envelopeNode : array(
                ready,
                "envelopes"
        )) {
            ObjectRef envelopeRef = ref(envelopeNode);
            JsonNode envelope = json(envelopeRef);
            String batchId = text(
                    envelope,
                    "batchId"
            );

            if (envelopeHashes
                    .put(
                            batchId,
                            envelopeRef
                                    .contentHash()
                    ) != null) {
                throw new CollectedRunInvalidException(
                        "envelopes",
                        "duplicate batch " + batchId
                );
            }

            outcomes
                    .addAll(batchOutcomes(
                            ready,
                            discoveryRef,
                            envelope,
                            batches
                                    .get(batchId)
                    ));
        }

        if (!envelopeHashes
                .keySet()
                .equals(batches
                        .keySet())) {
            throw new CollectedRunInvalidException(
                    "envelopes",
                    "batches " + envelopeHashes
                            .keySet() + " differ from discovery batches " + batches
                                    .keySet()
            );
        }

        requireRunTargets(
                discovery,
                outcomes
        );
        outcomes
                .sort(Comparator
                        .comparing(
                                outcome -> text(
                                        outcome,
                                        "documentId"
                                ),
                                CODE_POINT_ORDER
                        ));

        String snapshotHash = inputSnapshotHash(
                discoveryRef,
                envelopeHashes,
                outcomes
        );
        List<BuildRequest> requests = new ArrayList<>();

        for (JsonNode projection : array(
                ready,
                "projections"
        )) {
            requests
                    .add(request(
                            projection,
                            outcomes,
                            snapshotHash,
                            processingConfigHash
                    ));
        }

        return requests;
    }

    // envelope 하나의 outcome 줄, batch targetList와 집합, 개수, run 식별자 대조
    private List<JsonNode> batchOutcomes(
            JsonNode ready,
            ObjectRef discoveryRef,
            JsonNode envelope,
            JsonNode batch
    ) {

        String batchId = text(
                envelope,
                "batchId"
        );

        if (batch == null) {
            throw new CollectedRunInvalidException(
                    "envelope",
                    "batch " + batchId + " is not in discovery"
            );
        }

        requireSame(
                ready,
                envelope,
                "runId"
        );
        requireSame(
                ready,
                envelope,
                "snapshotId"
        );

        if (!ref(
                envelope,
                "discovery"
        )
                .equals(discoveryRef)) {
            throw new CollectedRunInvalidException(
                    "envelope",
                    "batch " + batchId + " refers to another discovery"
            );
        }

        ObjectRef targetsRef = ref(
                envelope,
                "targetList"
        );

        if (!targetsRef
                .equals(ref(
                        batch,
                        "targetList"
                ))) {
            throw new CollectedRunInvalidException(
                    "envelope",
                    "batch " + batchId + " targetList differs from discovery"
            );
        }

        List<JsonNode> targets = lines(targetsRef);
        List<JsonNode> outcomes = lines(ref(
                envelope,
                "jsonl"
        ));
        long targetCount = number(
                envelope,
                "targetCount"
        );
        long successCount = number(
                envelope,
                "successCount"
        );
        long successLines = outcomes
                .stream()
                .filter(CollectedRunService::succeeded)
                .count();

        if (targetCount != successCount + number(
                envelope,
                "failureCount"
        ) || targetCount != outcomes
                .size() || targetCount != targets
                        .size() || targetCount != number(
                                batch,
                                "targetCount"
                        ) || successCount != successLines) {
            throw new CollectedRunInvalidException(
                    "envelope",
                    "batch " + batchId + " counts do not match its lines"
            );
        }

        Set<String> outcomeIds = documentIds(outcomes);

        if (!documentIds(targets)
                .equals(outcomeIds) || outcomeIds
                        .size() != outcomes
                                .size()) {
            throw new CollectedRunInvalidException(
                    "envelope",
                    "batch " + batchId + " outcomes differ from its targets"
            );
        }

        for (JsonNode outcome : outcomes) {
            requireSame(
                    ready,
                    outcome,
                    "runId"
            );
            requireSame(
                    ready,
                    outcome,
                    "snapshotId"
            );
            requireSame(
                    envelope,
                    outcome,
                    "batchId"
            );
        }

        return outcomes;
    }

    // 회차 targetList가 batch 대상의 합집합이고 documentId 중복 없음
    private void requireRunTargets(
            JsonNode discovery,
            List<JsonNode> outcomes
    ) {

        List<JsonNode> targets = lines(ref(
                discovery,
                "targetList"
        ));
        Set<String> targetIds = documentIds(targets);

        if (targetIds
                .size() != targets
                        .size() || targets
                                .size() != outcomes
                                        .size() || number(
                                                discovery,
                                                "targetCount"
                                        ) != targets
                                                .size() || !targetIds
                                                        .equals(documentIds(outcomes))) {
            throw new CollectedRunInvalidException(
                    "discovery",
                    "run targetList is not the union of batch targets"
            );
        }
    }

    // projection 하나의 Build 요청, 필수 실패가 있으면 CollectedRunIncompleteException
    private BuildRequest request(
            JsonNode projection,
            List<JsonNode> outcomes,
            String snapshotHash,
            String processingConfigHash
    ) {

        JsonNode scope = object(
                projection,
                "packScope"
        );
        List<String> failedIds = new ArrayList<>();
        List<String> errorCodes = new ArrayList<>();
        List<SourceEntry> sources = new ArrayList<>();

        for (JsonNode outcome : outcomes) {
            JsonNode input = object(
                    outcome,
                    "input"
            );

            if (!sameScope(
                    scope,
                    input
            )) {
                continue;
            }

            if (succeeded(outcome)) {
                sources
                        .add(source(outcome));
            } else if (flag(
                    input,
                    "required"
            )) {
                failedIds
                        .add(text(
                                outcome,
                                "documentId"
                        ));
                errorCodes
                        .add(text(
                                outcome,
                                "errorCode"
                        ));
            }
        }

        if (!failedIds
                .isEmpty()) {
            throw new CollectedRunIncompleteException(
                    failedIds,
                    errorCodes
            );
        }

        return new BuildRequest(
                text(
                        scope,
                        "project"
                ),
                text(
                        scope,
                        "exactVersion"
                ),
                text(
                        scope,
                        "applicability"
                ),
                text(
                        projection,
                        "inputScopeKey"
                ),
                snapshotHash,
                processingConfigHash,
                null,
                sources
        );
    }

    // SUCCESS outcome 하나의 Build 원본
    private SourceEntry source(JsonNode outcome) {

        String url = text(
                outcome,
                "url"
        );
        String finalUrl = optionalText(
                outcome,
                "finalUrl"
        );
        ObjectRef raw = ref(
                outcome,
                "raw"
        );

        return new SourceEntry(
                text(
                        outcome,
                        "documentId"
                ),
                documentType(text(
                        object(
                                outcome,
                                "input"
                        ),
                        "documentType"
                )),
                url,
                url,
                finalUrl == null ? url : finalUrl,
                text(
                        outcome,
                        "snapshotId"
                ),
                raw
                        .contentHash(),
                raw
                        .objectKey()
        );
    }

    // run 입력 snapshot hash, batch는 batchId 순, 문서는 documentId 순
    private String inputSnapshotHash(
            ObjectRef discoveryRef,
            Map<String, String> envelopeHashes,
            List<JsonNode> outcomes
    ) {

        List<String> fields = new ArrayList<>();
        fields
                .add(INPUT_SNAPSHOT_DOMAIN);
        fields
                .add(discoveryRef
                        .contentHash());
        fields
                .add(Integer
                        .toString(envelopeHashes
                                .size()));
        envelopeHashes
                .forEach((batchId, envelopeHash) -> {
                    fields
                            .add(batchId);
                    fields
                            .add(envelopeHash);
                });
        fields
                .add(Integer
                        .toString(outcomes
                                .size()));

        for (JsonNode outcome : outcomes) {
            fields
                    .add(text(
                            outcome,
                            "documentId"
                    ));
            fields
                    .add(succeeded(outcome) ? ref(
                            outcome,
                            "raw"
                    )
                            .contentHash() : NO_RAW);
            fields
                    .add(CanonicalJson
                            .sha256(CanonicalJson
                                    .canonicalize(jsonMapper
                                            .writeValueAsString(object(
                                                    outcome,
                                                    "versionEvidence"
                                            )))));
        }

        return IdHash
                .hash(fields
                        .toArray(String[]::new));
    }

    // discovery batch를 batchId별로, 중복이면 오류
    private static Map<String, JsonNode> batches(JsonNode discovery) {

        Map<String, JsonNode> batches = new HashMap<>();

        for (JsonNode batch : array(
                discovery,
                "batches"
        )) {
            if (batches
                    .put(
                            text(
                                    batch,
                                    "batchId"
                            ),
                            batch
                    ) != null) {
                throw new CollectedRunInvalidException(
                        "discovery",
                        "duplicate batch"
                );
            }
        }

        return batches;
    }

    // contracts 수집 문서 유형을 Pack 문서 유형으로, 모르는 값은 CollectedRunInvalidException
    private static DocumentType documentType(String collected) {

        return switch (collected) {
            case "REFERENCE" -> DocumentType.REFERENCE;
            case "APPLICATION_PROPERTIES" -> DocumentType.PROPERTIES;
            case "DEPENDENCY_VERSIONS" -> DocumentType.DEPENDENCIES;
            case "PROJECT_OVERVIEW" -> DocumentType.PROJECT_OVERVIEW;
            case "GUIDE" -> DocumentType.GUIDE;
            case "RELEASE_NOTES" -> DocumentType.RELEASE_NOTE;
            case "MIGRATION_GUIDE" -> DocumentType.MIGRATION;
            case "CONFIG_METADATA" -> DocumentType.CONFIG_METADATA;
            case "JAVADOC" -> DocumentType.JAVADOC;
            case "EXAMPLE" -> DocumentType.EXAMPLE;
            default -> throw new CollectedRunInvalidException(
                    "documentType",
                    collected
            );
        };
    }

    // objectRef가 가리키는 bytes, 없거나 크기나 hash가 다르면 오류
    private byte[] bytes(ObjectRef ref) {

        byte[] bytes = objectStore
                .read(ref
                        .objectKey())
                .orElseThrow(() -> new CollectedRunInvalidException(
                        "MISSING_OBJECT",
                        ref
                                .objectKey()
                ));

        if (bytes.length != ref
                .sizeBytes() || !sha256(bytes)
                        .equals(ref
                                .contentHash())) {
            throw new CollectedRunInvalidException(
                    "objectRef",
                    "size or hash differs for " + ref
                            .objectKey()
            );
        }

        return bytes;
    }

    // 검증한 JSON 객체 하나
    private JsonNode json(ObjectRef ref) {

        return parse(
                new String(
                        bytes(ref),
                        StandardCharsets.UTF_8
                ),
                ref
        );
    }

    // 검증한 JSONL의 줄마다 JSON 객체
    private List<JsonNode> lines(ObjectRef ref) {

        return new String(
                bytes(ref),
                StandardCharsets.UTF_8
        )
                .lines()
                .map(line -> parse(
                        line,
                        ref
                ))
                .toList();
    }

    // JSON 객체 텍스트 해석, 객체가 아니거나 형식 오류면 오류
    private JsonNode parse(
            String text,
            ObjectRef ref
    ) {

        try {
            JsonNode node = jsonMapper
                    .readTree(text);

            if (!node
                    .isObject()) {
                throw new CollectedRunInvalidException(
                        "json",
                        ref
                                .objectKey() + " is not an object"
                );
            }

            return node;
        } catch (JacksonException exception) {
            throw new CollectedRunInvalidException(
                    "json",
                    ref
                            .objectKey() + " " + exception
                                    .getOriginalMessage()
            );
        }
    }

    // 두 문서의 같은 필드가 같아야 통과
    private static void requireSame(
            JsonNode expected,
            JsonNode actual,
            String field
    ) {

        if (actual
                .get(field) == null || !actual
                        .get(field)
                        .equals(expected
                                .get(field))) {
            throw new CollectedRunInvalidException(
                    field,
                    "differs between linked manifests"
            );
        }
    }

    // outcome이 SUCCESS인지
    private static boolean succeeded(JsonNode outcome) {

        return "SUCCESS"
                .equals(text(
                        outcome,
                        "outcome"
                ));
    }

    // input이 projection의 project, exactVersion, applicability와 같은지
    private static boolean sameScope(
            JsonNode scope,
            JsonNode input
    ) {

        return List
                .of(
                        "project",
                        "exactVersion",
                        "applicability"
                )
                .stream()
                .allMatch(field -> text(
                        scope,
                        field
                )
                        .equals(text(
                                input,
                                field
                        )));
    }

    // 줄마다 documentId 집합
    private static Set<String> documentIds(List<JsonNode> lines) {

        Set<String> ids = new HashSet<>();

        for (JsonNode line : lines) {
            ids
                    .add(text(
                            line,
                            "documentId"
                    ));
        }

        return ids;
    }

    // 객체 필드의 objectRef
    private static ObjectRef ref(
            JsonNode node,
            String field
    ) {

        return ref(object(
                node,
                field
        ));
    }

    // objectRef 객체
    private static ObjectRef ref(JsonNode node) {

        return new ObjectRef(
                text(
                        node,
                        "objectKey"
                ),
                text(
                        node,
                        "contentHash"
                ),
                number(
                        node,
                        "sizeBytes"
                )
        );
    }

    // 필수 객체 필드
    private static JsonNode object(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .get(field);

        if (value == null || !value
                .isObject()) {
            throw new CollectedRunInvalidException(
                    field,
                    "object is required"
            );
        }

        return value;
    }

    // 필수 배열 필드의 원소
    private static List<JsonNode> array(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .get(field);

        if (value == null || !value
                .isArray()) {
            throw new CollectedRunInvalidException(
                    field,
                    "array is required"
            );
        }

        return List
                .copyOf(value
                        .values());
    }

    // 필수 문자열 필드
    private static String text(
            JsonNode node,
            String field
    ) {

        String value = optionalText(
                node,
                field
        );

        if (value == null) {
            throw new CollectedRunInvalidException(
                    field,
                    "string is required"
            );
        }

        return value;
    }

    // 문자열 또는 null 필드, 다른 형식이면 오류
    private static String optionalText(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .get(field);

        if (value == null || value
                .isNull()) {
            return null;
        }

        if (!value
                .isString()) {
            throw new CollectedRunInvalidException(
                    field,
                    "string is required"
            );
        }

        return value
                .stringValue();
    }

    // 필수 정수 필드
    private static long number(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .get(field);

        if (value == null || !value
                .isIntegralNumber() || !value
                        .canConvertToLong()) {
            throw new CollectedRunInvalidException(
                    field,
                    "integer is required"
            );
        }

        return value
                .longValue();
    }

    // 필수 boolean 필드
    private static boolean flag(
            JsonNode node,
            String field
    ) {

        JsonNode value = node
                .get(field);

        if (value == null || !value
                .isBoolean()) {
            throw new CollectedRunInvalidException(
                    field,
                    "boolean is required"
            );
        }

        return value
                .booleanValue();
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
            throw new IllegalStateException(
                    "SHA-256 is required by the Java platform",
                    exception
            );
        }
    }
}
