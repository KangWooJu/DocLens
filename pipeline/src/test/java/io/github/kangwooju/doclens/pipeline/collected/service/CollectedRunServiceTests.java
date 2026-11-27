package io.github.kangwooju.doclens.pipeline.collected.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunIncompleteException;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunInvalidException;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.pack.store.LocalObjectStore;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures.Bundle;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures.Document;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures.Parts;
import io.github.kangwooju.doclens.testsupport.PackRequestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("collector readyRun을 Build 요청으로 바꾸는 검증과 대응")
class CollectedRunServiceTests {

    private static final String CONFIG_HASH = "sha256:" + "c"
            .repeat(64);

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    private static final String OTHER_COMMIT = "f"
            .repeat(40);

    private static final String PAGE_URL = ParseFixtures.RAW_ROOT + ParseFixtures.PAGES + "sample.adoc";

    private static final Path CONTRACTS = Path
            .of(System
                    .getProperty("doclens.contractsDir"));

    @TempDir
    Path root;

    @Test
    @DisplayName("contracts root100은 필수 문서 실패 3건으로 Build 없이 입력 보류")
    void root100RequiredFailuresAreIncomplete() throws IOException {

        Path examples = CONTRACTS
                .resolve("examples/root100");
        Map<String, String> files = Map
                .of(
                        "discovery/fixture-root100/discovery.json",
                        "discovery.json",
                        "manifests/fixture-root100/envelope.json",
                        "envelope.json",
                        "manifests/fixture-root100/targets.jsonl",
                        "targets.jsonl",
                        "manifests/fixture-root100/outcomes.jsonl",
                        "outcomes.jsonl",
                        "manifests/fixture-root100/ready-run.json",
                        "ready-run.json"
                );
        Map<String, byte[]> objects = new LinkedHashMap<>();

        for (Map.Entry<String, String> file : files
                .entrySet()) {
            objects
                    .put(
                            file
                                    .getKey(),
                            Files
                                    .readAllBytes(examples
                                            .resolve(file
                                                    .getValue()))
                    );
        }

        store(objects);

        byte[] readyRun = objects
                .get("manifests/fixture-root100/ready-run.json");
        List<String> failed = new ArrayList<>();

        for (String line : Files
                .readAllLines(examples
                        .resolve("outcomes.jsonl"))) {
            JsonNode outcome = JSON_MAPPER
                    .readTree(line);

            if ("FAILED"
                    .equals(outcome
                            .get("outcome")
                            .stringValue())) {
                failed
                        .add(outcome
                                .get("documentId")
                                .stringValue());
            }
        }

        failed
                .sort(CanonicalJson::compareCodePoints);

        CollectedRunIncompleteException exception = assertThrows(
                CollectedRunIncompleteException.class,
                () -> service()
                        .requests(
                                new ObjectRef(
                                        "manifests/fixture-root100/ready-run.json",
                                        CollectedRunFixtures
                                                .sha256(readyRun),
                                        readyRun.length
                                ),
                                CONFIG_HASH
                        )
        );

        assertEquals(
                3,
                failed
                        .size()
        );
        assertEquals(
                failed,
                exception
                        .documentIds()
        );
    }

    @Test
    @DisplayName("실패 없는 readyRun은 projection 하나의 요청, 원본 대응 전부와 golden run 입력 hash")
    void mapsSucceededRun() {

        Bundle bundle = CollectedRunFixtures
                .bundle();
        store(bundle
                .objects());

        List<BuildRequest> requests = service()
                .requests(
                        bundle
                                .readyRun(),
                        CONFIG_HASH
                );

        assertEquals(
                1,
                requests
                        .size()
        );

        BuildRequest request = requests
                .getFirst();

        assertEquals(
                List
                        .of(
                                CollectedRunFixtures.PROJECT,
                                CollectedRunFixtures.VERSION,
                                "EXACT_RELEASE",
                                CollectedRunFixtures.INPUT_SCOPE_KEY,
                                CONFIG_HASH
                        ),
                List
                        .of(
                                request
                                        .project(),
                                request
                                        .exactVersion(),
                                request
                                        .applicability(),
                                request
                                        .inputScopeKey(),
                                request
                                        .processingConfigHash()
                        )
        );
        assertNull(request
                .modelChecksum());
        assertEquals(
                goldenId("input-snapshot-two-references"),
                request
                        .runInputSnapshotHash()
        );
        assertEquals(
                2,
                request
                        .sources()
                        .size()
        );
        assertTrue(CanonicalJson
                .compareCodePoints(
                        request
                                .sources()
                                .get(0)
                                .documentId(),
                        request
                                .sources()
                                .get(1)
                                .documentId()
                ) < 0);

        for (SourceEntry source : request
                .sources()) {
            String fixture = source
                    .canonicalUrl()
                    .substring(PackRequestFixtures.BASE_URL
                            .length());
            byte[] raw = PackRequestFixtures
                    .bytes(fixture);

            assertEquals(
                    DocumentType.REFERENCE,
                    source
                            .documentType()
            );
            assertEquals(
                    source
                            .canonicalUrl(),
                    source
                            .originalUrl()
            );
            assertEquals(
                    source
                            .canonicalUrl(),
                    source
                            .finalUrl()
            );
            assertEquals(
                    CollectedRunFixtures.SNAPSHOT_ID,
                    source
                            .sourceSnapshotId()
            );
            assertEquals(
                    CollectedRunFixtures
                            .sha256(raw),
                    source
                            .rawSha256()
            );
            assertArrayEquals(
                    raw,
                    bundle
                            .objects()
                            .get(source
                                    .rawObjectKey()),
                    "raw key " + source
                            .rawObjectKey()
            );
        }
    }

    @Test
    @DisplayName("선택 문서 실패는 원본에서 빠지고 run 입력 hash에는 남음")
    void optionalFailureStaysInSnapshot() {

        List<Document> documents = new ArrayList<>(CollectedRunFixtures
                .succeededReferences());
        documents
                .add(new Document(
                        "reference.html",
                        "APPLICATION_PROPERTIES",
                        false,
                        false
                ));

        Bundle bundle = CollectedRunFixtures
                .write(CollectedRunFixtures
                        .parts(documents));
        store(bundle
                .objects());

        BuildRequest request = service()
                .requests(
                        bundle
                                .readyRun(),
                        CONFIG_HASH
                )
                .getFirst();

        assertEquals(
                2,
                request
                        .sources()
                        .size()
        );
        assertNotEquals(
                goldenId("input-snapshot-two-references"),
                request
                        .runInputSnapshotHash()
        );
    }

    @Test
    @DisplayName("readyRun 크기나 hash가 다르거나 저장된 manifest가 바뀌면 검증 실패")
    void rejectsHashAndSizeMismatch() throws IOException {

        Bundle bundle = CollectedRunFixtures
                .bundle();
        store(bundle
                .objects());

        ObjectRef ref = bundle
                .readyRun();

        assertInvalid(new ObjectRef(
                ref
                        .objectKey(),
                ref
                        .contentHash(),
                ref
                        .sizeBytes() + 1
        ));
        assertInvalid(new ObjectRef(
                ref
                        .objectKey(),
                "sha256:" + "0"
                        .repeat(64),
                ref
                        .sizeBytes()
        ));

        Path outcomes = root
                .resolve("manifests/" + CollectedRunFixtures.RUN_ID + "/" + CollectedRunFixtures.BATCH_ID + "/outcomes.jsonl");
        byte[] original = Files
                .readAllBytes(outcomes);
        original[0] = (byte) ' ';
        Files
                .write(
                        outcomes,
                        original
                );

        assertInvalid(ref);
    }

    @Test
    @DisplayName("hash가 맞아도 대상과 결과 집합, 개수, batch 집합이 어긋나면 검증 실패")
    void rejectsSetAndCountMismatch() {

        Parts renamed = CollectedRunFixtures
                .parts(CollectedRunFixtures
                        .succeededReferences());
        renamed
                .outcomes()
                .getFirst()
                .put(
                        "documentId",
                        "sha256:" + "d"
                                .repeat(64)
                );
        assertInvalid(renamed);

        Parts counted = CollectedRunFixtures
                .parts(CollectedRunFixtures
                        .succeededReferences());
        counted
                .envelope()
                .put(
                        "targetCount",
                        3
                );
        assertInvalid(counted);

        Parts dropped = CollectedRunFixtures
                .parts(CollectedRunFixtures
                        .succeededReferences());
        dropped
                .outcomes()
                .removeLast();
        assertInvalid(dropped);

        Parts otherBatch = CollectedRunFixtures
                .parts(CollectedRunFixtures
                        .succeededReferences());
        otherBatch
                .envelope()
                .put(
                        "batchId",
                        "55555555-5555-4555-8555-555555555555"
                );
        assertInvalid(otherBatch);
    }

    @Test
    @DisplayName("참조 manifest 객체가 없으면 MISSING_OBJECT 검증 실패")
    void rejectsMissingObject() throws IOException {

        Bundle bundle = CollectedRunFixtures
                .bundle();
        store(bundle
                .objects());
        Files
                .delete(root
                        .resolve("discovery/" + CollectedRunFixtures.RUN_ID + "/targets.jsonl"));

        CollectedRunInvalidException exception = assertInvalid(bundle
                .readyRun());

        assertTrue(exception
                .getMessage()
                .contains("MISSING_OBJECT"));
    }

    @Test
    @DisplayName("contracts 문서 유형 10개는 Pack 문서 유형으로, 모르는 값은 검증 실패")
    void mapsEveryDocumentType() {

        Map<String, DocumentType> expected = new LinkedHashMap<>();
        expected
                .put(
                        "REFERENCE",
                        DocumentType.REFERENCE
                );
        expected
                .put(
                        "APPLICATION_PROPERTIES",
                        DocumentType.PROPERTIES
                );
        expected
                .put(
                        "DEPENDENCY_VERSIONS",
                        DocumentType.DEPENDENCIES
                );
        expected
                .put(
                        "PROJECT_OVERVIEW",
                        DocumentType.PROJECT_OVERVIEW
                );
        expected
                .put(
                        "GUIDE",
                        DocumentType.GUIDE
                );
        expected
                .put(
                        "RELEASE_NOTES",
                        DocumentType.RELEASE_NOTE
                );
        expected
                .put(
                        "MIGRATION_GUIDE",
                        DocumentType.MIGRATION
                );
        expected
                .put(
                        "CONFIG_METADATA",
                        DocumentType.CONFIG_METADATA
                );
        expected
                .put(
                        "JAVADOC",
                        DocumentType.JAVADOC
                );
        expected
                .put(
                        "EXAMPLE",
                        DocumentType.EXAMPLE
                );

        for (Map.Entry<String, DocumentType> entry : expected
                .entrySet()) {
            Bundle bundle = typed(entry
                    .getKey());
            store(bundle
                    .objects());

            BuildRequest request = service()
                    .requests(
                            bundle
                                    .readyRun(),
                            CONFIG_HASH
                    )
                    .getFirst();
            List<SourceEntry> entries = entry
                    .getValue() == DocumentType.EXAMPLE ? request
                            .includes() : request
                                    .sources();

            assertEquals(
                    entry
                            .getValue(),
                    entries
                            .getFirst()
                            .documentType(),
                    entry
                            .getKey()
            );
        }

        Bundle unknown = typed("BLOG_POST");
        store(unknown
                .objects());

        assertInvalid(unknown
                .readyRun());
    }

    @Test
    @DisplayName("HTML readyRun은 include 없음, 예시 문서는 Pack 원본이 아니라 include")
    void splitsExamplesIntoIncludes() {

        Bundle html = CollectedRunFixtures
                .bundle();
        store(html
                .objects());

        assertEquals(
                List
                        .of(),
                service()
                        .requests(
                                html
                                        .readyRun(),
                                CONFIG_HASH
                        )
                        .getFirst()
                        .includes()
        );

        List<Document> documents = CollectedRunFixtures
                .succeededGitDocuments();
        BuildRequest request = gitRequest(CollectedRunFixtures
                .gitParts(documents));

        assertEquals(
                List
                        .of(
                                DocumentType.REFERENCE,
                                DocumentType.REFERENCE
                        ),
                request
                        .sources()
                        .stream()
                        .map(SourceEntry::documentType)
                        .toList()
        );
        assertEquals(
                documents
                        .size() - 2,
                request
                        .includes()
                        .size()
        );
        assertTrue(request
                .includes()
                .stream()
                .allMatch(include -> include
                        .documentType() == DocumentType.EXAMPLE));
        assertTrue(request
                .includes()
                .stream()
                .anyMatch(include -> include
                        .canonicalUrl()
                        .equals(ParseFixtures.RAW_ROOT + "gradle.properties")));
    }

    @Test
    @DisplayName("필수 예시 실패는 버전 판정보다 먼저 입력 보류")
    void requiredExampleFailureIsIncompleteFirst() {

        List<Document> documents = new ArrayList<>(CollectedRunFixtures
                .succeededGitDocuments());
        documents
                .set(
                        documents
                                .size() - 1,
                        new Document(
                                "gradle.properties",
                                "EXAMPLE",
                                true,
                                false
                        )
                );

        Bundle bundle = CollectedRunFixtures
                .write(CollectedRunFixtures
                        .gitParts(documents));
        store(bundle
                .objects());

        assertThrows(
                CollectedRunIncompleteException.class,
                () -> service()
                        .requests(
                                bundle
                                        .readyRun(),
                                CONFIG_HASH
                        )
        );
    }

    @Test
    @DisplayName("실패한 선택 문서의 commit은 버전 판정 대상이 아님")
    void ignoresFailedOutcomesInVersionCheck() {

        List<Document> documents = new ArrayList<>(CollectedRunFixtures
                .succeededGitDocuments());
        documents
                .add(new Document(
                        ParseFixtures.PAGES + "broken-tag.adoc",
                        "REFERENCE",
                        false,
                        false
                ));

        Parts parts = CollectedRunFixtures
                .gitParts(documents);
        parts
                .outcomes()
                .getLast()
                .put(
                        "sourceCommit",
                        OTHER_COMMIT
                );

        assertEquals(
                2,
                gitRequest(parts)
                        .sources()
                        .size()
        );
    }

    @Test
    @DisplayName("gradle.properties outcome이 없거나 둘이면 검증 실패")
    void requiresOneGradleProperties() {

        List<Document> withoutProperties = new ArrayList<>(CollectedRunFixtures
                .succeededGitDocuments());
        withoutProperties
                .removeLast();
        assertInvalid(CollectedRunFixtures
                .gitParts(withoutProperties));

        Parts twice = gitParts();
        outcome(
                twice,
                PAGE_URL
        )
                .put(
                        "url",
                        "https://raw.githubusercontent.com/spring-projects/other/" + CollectedRunFixtures.COMMIT + "/gradle.properties"
                );
        assertInvalid(twice);
    }

    @Test
    @DisplayName("봉인된 gradle.properties bytes의 version이 범위 버전과 다르면 검증 실패")
    void rejectsGradleVersionMismatch() {

        Parts parts = gitParts();
        CollectedRunFixtures
                .replaceRaw(
                        parts,
                        outcome(
                                parts,
                                ParseFixtures.RAW_ROOT + "gradle.properties"
                        ),
                        "version=4.1.3\n"
                                .getBytes(StandardCharsets.UTF_8)
                );

        assertInvalid(parts);
    }

    @Test
    @DisplayName("officialRevision, run 안 commit, URL과 sourceCommit 중 하나라도 다르면 검증 실패")
    void rejectsCommitMismatch() {

        Parts revision = gitParts();
        revision
                .discovery()
                .put(
                        "officialRevision",
                        OTHER_COMMIT
                );
        assertInvalid(revision);

        Parts twoCommits = gitParts();
        Map<String, Object> other = outcome(
                twoCommits,
                PAGE_URL
        );
        other
                .put(
                        "url",
                        PAGE_URL
                                .replace(
                                        CollectedRunFixtures.COMMIT,
                                        OTHER_COMMIT
                                )
                );
        other
                .put(
                        "sourceCommit",
                        OTHER_COMMIT
                );
        assertInvalid(twoCommits);

        Parts sourceCommit = gitParts();
        outcome(
                sourceCommit,
                PAGE_URL
        )
                .put(
                        "sourceCommit",
                        OTHER_COMMIT
                );
        assertInvalid(sourceCommit);
    }

    @Test
    @DisplayName("모르는 버전 규칙이나 commit 고정이 아닌 근거 방법은 검증 실패")
    @SuppressWarnings("unchecked")
    void rejectsUnknownVersionRule() {

        Parts rule = gitParts();
        ((Map<String, Object>) outcome(
                rule,
                PAGE_URL
        )
                .get("versionEvidence"))
                .put(
                        "selectorRuleId",
                        "antora-meta-version-v1"
                );
        assertInvalid(rule);

        Parts method = gitParts();
        ((Map<String, Object>) outcome(
                method,
                PAGE_URL
        )
                .get("versionEvidence"))
                .put(
                        "method",
                        "PAGE_MARKER"
                );
        assertInvalid(method);
    }

    // 실패 없는 git blob 문서의 manifest 내용
    private static Parts gitParts() {

        return CollectedRunFixtures
                .gitParts(CollectedRunFixtures
                        .succeededGitDocuments());
    }

    // URL이 같은 outcome 하나
    private static Map<String, Object> outcome(
            Parts parts,
            String url
    ) {

        return parts
                .outcomes()
                .stream()
                .filter(outcome -> url
                        .equals(outcome
                                .get("url")))
                .findFirst()
                .orElseThrow();
    }

    // 묶어 저장한 git blob readyRun의 요청 하나
    private BuildRequest gitRequest(Parts parts) {

        Bundle bundle = CollectedRunFixtures
                .write(parts);
        store(bundle
                .objects());

        return service()
                .requests(
                        bundle
                                .readyRun(),
                        CONFIG_HASH
                )
                .getFirst();
    }

    // 문서 유형 하나의 성공 문서 하나 묶음
    private static Bundle typed(String documentType) {

        return CollectedRunFixtures
                .write(CollectedRunFixtures
                        .parts(List
                                .of(new Document(
                                        "reference.html",
                                        documentType,
                                        true,
                                        true
                                ))));
    }

    // 바꾼 내용을 다시 묶어 저장한 뒤 검증 실패 확인
    private void assertInvalid(Parts parts) {

        Bundle bundle = CollectedRunFixtures
                .write(parts);
        store(bundle
                .objects());

        assertInvalid(bundle
                .readyRun());
    }

    // readyRun 검증 실패
    private CollectedRunInvalidException assertInvalid(ObjectRef readyRun) {

        return assertThrows(
                CollectedRunInvalidException.class,
                () -> service()
                        .requests(
                                readyRun,
                                CONFIG_HASH
                        )
        );
    }

    // 임시 object root의 Local 저장소 서비스
    private CollectedRunService service() {

        return new CollectedRunService(
                new LocalObjectStore(root),
                JSON_MAPPER
        );
    }

    // object key별 bytes를 임시 object root에 씀, 같은 key는 덮어씀
    private void store(Map<String, byte[]> objects) {

        objects
                .forEach((key, bytes) -> {
                    Path file = root
                            .resolve(key);

                    try {
                        Files
                                .createDirectories(file
                                        .getParent());
                        Files
                                .write(
                                        file,
                                        bytes
                                );
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
    }

    // contracts id golden vector의 ID
    private static String goldenId(String name) {

        for (JsonNode vector : JSON_MAPPER
                .readTree(CONTRACTS
                        .resolve("id-golden-vectors.json")
                        .toFile())
                .values()) {
            if (name
                    .equals(vector
                            .get("name")
                            .stringValue())) {
                return vector
                        .get("id")
                        .stringValue();
            }
        }

        throw new IllegalStateException("missing golden vector " + name);
    }
}
