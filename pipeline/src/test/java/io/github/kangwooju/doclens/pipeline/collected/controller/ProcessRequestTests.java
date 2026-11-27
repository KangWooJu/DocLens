package io.github.kangwooju.doclens.pipeline.collected.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.pipeline.pack.BuildFixtures;
import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.testsupport.CallerTokens;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures;
import io.github.kangwooju.doclens.testsupport.CollectedRunFixtures.Bundle;
import io.github.kangwooju.doclens.testsupport.PackRequestFixtures;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@DisplayName("실제 socket으로 보낸 processRequest와 최신 발행 조회")
class ProcessRequestTests {

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .build();

    private static final String SCHEDULER_TOKEN = CallerTokens
            .newToken();

    private static final String OPERATOR_TOKEN = CallerTokens
            .newToken();

    private static final String PROCESSING_JOBS = "/internal/v1/processing-jobs";

    private static final String LATEST = "/internal/v1/publications/latest?project=spring-boot&exactVersion=4.1.2";

    private static final HttpClient CLIENT = HttpClient
            .newHttpClient();

    @TempDir
    static Path objectRoot;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PackBuildFacade facade;

    private Bundle bundle;

    // 시험 object root와 호출자 토큰 hash 지정
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.pipeline.object-root",
                        () -> objectRoot
                                .toString()
                );
        registry
                .add(
                        "doclens.security.caller-tokens",
                        () -> CallerTokens
                                .entry(
                                        "scheduler",
                                        SCHEDULER_TOKEN
                                ) + "," + CallerTokens
                                        .entry(
                                                "operator",
                                                OPERATOR_TOKEN
                                        )
                );
    }

    // 시험마다 표와 파일 비우고 합성 readyRun 묶음을 local 저장소에 쓰기
    @BeforeEach
    void prepare() throws IOException {

        jdbcClient
                .sql("TRUNCATE pipeline.builds CASCADE")
                .update();

        try (Stream<Path> files = Files
                .walk(objectRoot)) {
            for (Path path : files
                    .sorted((left, right) -> right
                            .compareTo(left))
                    .toList()) {
                if (!path
                        .equals(objectRoot)) {
                    Files
                            .delete(path);
                }
            }
        }

        bundle = CollectedRunFixtures
                .bundle();

        for (Map.Entry<String, byte[]> object : bundle
                .objects()
                .entrySet()) {
            write(
                    object
                            .getKey(),
                    object
                            .getValue()
            );
        }
    }

    @Test
    @DisplayName("정상 처리는 200 receipt, 결과 객체는 발행 번호 1의 manifest이고 builds 행과 계약 필드가 맞음")
    void processesRequest() throws Exception {

        HttpResponse<String> response = post(
                SCHEDULER_TOKEN,
                body(0)
        );

        assertEquals(
                200,
                response
                        .statusCode()
        );

        JsonNode receipt = JSON
                .readTree(response
                        .body());
        JsonNode result = receipt
                .get("projectionResults")
                .get(0);
        String buildId = result
                .get("buildId")
                .stringValue();
        JsonNode ref = receipt
                .get("resultObjectRef");
        byte[] manifestBytes = Files
                .readAllBytes(objectRoot
                        .resolve(ref
                                .get("objectKey")
                                .stringValue()));
        JsonNode manifest = JSON
                .readTree(manifestBytes);

        assertEquals(
                requiredFields("processingRunResultReceipt"),
                fieldNames(receipt)
        );
        assertEquals(
                Set
                        .of(
                                "packScope",
                                "state",
                                "buildId",
                                "buildJobId",
                                "activationJobId"
                        ),
                fieldNames(result)
        );
        assertEquals(
                "SUCCEEDED",
                result
                        .get("state")
                        .stringValue()
        );
        assertEquals(
                buildId,
                result
                        .get("buildJobId")
                        .stringValue()
        );
        assertTrue(result
                .get("activationJobId")
                .isNull());
        assertEquals(
                "packs/spring-boot-4.1.2/publications/1.json",
                ref
                        .get("objectKey")
                        .stringValue()
        );
        assertEquals(
                CollectedRunFixtures
                        .sha256(manifestBytes),
                ref
                        .get("contentHash")
                        .stringValue()
        );
        assertEquals(
                manifestBytes.length,
                ref
                        .get("sizeBytes")
                        .longValue()
        );

        Map<String, Object> row = jdbcClient
                .sql("SELECT build_id::text, run_input_snapshot_hash, pack_object_key, publication_sequence FROM pipeline.builds")
                .query()
                .singleRow();

        assertEquals(
                buildId,
                row
                        .get("build_id")
        );
        assertEquals(
                1L,
                row
                        .get("publication_sequence")
        );
        assertEquals(
                row
                        .get("run_input_snapshot_hash"),
                receipt
                        .get("runInputSnapshotHash")
                        .stringValue()
        );
        assertEquals(
                buildId,
                manifest
                        .get("buildId")
                        .stringValue()
        );
        assertEquals(
                1,
                manifest
                        .get("publicationSequence")
                        .intValue()
        );
        assertEquals(
                row
                        .get("pack_object_key"),
                manifest
                        .get("objectKey")
                        .stringValue()
        );
    }

    @Test
    @DisplayName("같은 본문 재전송은 같은 Build의 receipt, Build 하나, manifest 하나, Pack 파일을 다시 쓰지 않음")
    void replaysSameRequest() throws Exception {

        String body = body(0);
        HttpResponse<String> first = post(
                SCHEDULER_TOKEN,
                body
        );
        Path pack = packFile();
        FileTime written = Files
                .getLastModifiedTime(pack);
        HttpResponse<String> second = post(
                SCHEDULER_TOKEN,
                body
        );

        assertEquals(
                200,
                second
                        .statusCode()
        );
        assertEquals(
                JSON
                        .readTree(first
                                .body()),
                JSON
                        .readTree(second
                                .body())
        );
        assertEquals(
                1,
                buildCount()
        );
        assertEquals(
                1,
                manifestCount()
        );
        assertEquals(
                written,
                Files
                        .getLastModifiedTime(pack)
        );
    }

    @Test
    @DisplayName("같은 본문을 두 스레드가 함께 보내도 Build 하나, Pack 하나, 둘 다 같은 200 receipt")
    void replaysConcurrentRequests() throws Exception {

        String body = body(0);
        List<HttpResponse<String>> responses;

        try (ExecutorService executor = Executors
                .newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> futures = executor
                    .invokeAll(List
                            .of(
                                    () -> post(
                                            SCHEDULER_TOKEN,
                                            body
                                    ),
                                    () -> post(
                                            SCHEDULER_TOKEN,
                                            body
                                    )
                            ));
            responses = List
                    .of(
                            futures
                                    .get(0)
                                    .get(),
                            futures
                                    .get(1)
                                    .get()
                    );
        }

        for (HttpResponse<String> response : responses) {
            assertEquals(
                    200,
                    response
                            .statusCode()
            );
        }

        assertEquals(
                JSON
                        .readTree(responses
                                .get(0)
                                .body()),
                JSON
                        .readTree(responses
                                .get(1)
                                .body())
        );
        assertEquals(
                1,
                buildCount()
        );
        packFile();
    }

    @Test
    @DisplayName("처리 설정 hash가 다르면 409 HASH_MISMATCH 재시도 불가, message에 현재 hash, Build 행 없음")
    void rejectsConfigMismatch() throws Exception {

        HttpResponse<String> response = post(
                SCHEDULER_TOKEN,
                CollectedRunFixtures
                        .processRequest(
                                bundle,
                                "sha256:" + "0"
                                        .repeat(64),
                                0
                        )
        );
        JsonNode error = error(
                response,
                409,
                "HASH_MISMATCH",
                false
        );

        assertTrue(error
                .get("message")
                .stringValue()
                .contains(BuildFixtures
                        .currentHash()));
        assertEquals(
                0,
                buildCount()
        );
    }

    @Test
    @DisplayName("원본 객체가 없으면 503 재시도 가능, Build RUNNING, 객체를 되돌린 뒤 재전송은 200")
    void waitsForMissingRaw() throws Exception {

        String rawKey = bundle
                .objects()
                .keySet()
                .stream()
                .filter(key -> key
                        .startsWith("raw/"))
                .findFirst()
                .orElseThrow();
        Files
                .delete(objectRoot
                        .resolve(rawKey));
        String body = body(0);

        error(
                post(
                        SCHEDULER_TOKEN,
                        body
                ),
                503,
                "INPUT_INCOMPLETE",
                true
        );
        assertEquals(
                "RUNNING",
                jdbcClient
                        .sql("SELECT status FROM pipeline.builds")
                        .query(String.class)
                        .single()
        );

        write(
                rawKey,
                bundle
                        .objects()
                        .get(rawKey)
        );

        assertEquals(
                200,
                post(
                        SCHEDULER_TOKEN,
                        body
                )
                        .statusCode()
        );
        assertEquals(
                1,
                buildCount()
        );
    }

    @Test
    @DisplayName("최신 조회는 발행 전 404, 백필처럼 manifest 객체 없는 발행은 행에서 같은 bytes를 만들어 게시, 다음 발행은 번호 2")
    void servesLatestPublication() throws Exception {

        error(
                get(
                        OPERATOR_TOKEN,
                        LATEST
                ),
                404,
                "NOT_FOUND",
                false
        );

        for (SourceEntry source : PackRequestFixtures
                .sources()) {
            PackRequestFixtures
                    .writeRaw(
                            objectRoot,
                            source
                    );
        }

        facade
                .build(PackRequestFixtures
                        .request(PackRequestFixtures
                                .sources()));
        Path first = objectRoot
                .resolve("packs/spring-boot-4.1.2/publications/1.json");

        assertFalse(Files
                .exists(first));

        HttpResponse<String> latestFirst = get(
                OPERATOR_TOKEN,
                LATEST
        );

        assertEquals(
                200,
                latestFirst
                        .statusCode()
        );
        assertArrayEquals(
                Files
                        .readAllBytes(first),
                latestFirst
                        .body()
                        .getBytes(StandardCharsets.UTF_8)
        );

        error(
                post(
                        SCHEDULER_TOKEN,
                        body(0)
                ),
                409,
                "STALE_OWNER",
                false
        );
        assertEquals(
                200,
                post(
                        SCHEDULER_TOKEN,
                        body(1)
                )
                        .statusCode()
        );

        HttpResponse<String> latestSecond = get(
                SCHEDULER_TOKEN,
                LATEST
        );

        assertEquals(
                2,
                JSON
                        .readTree(latestSecond
                                .body())
                        .get("publicationSequence")
                        .intValue()
        );
        assertArrayEquals(
                Files
                        .readAllBytes(objectRoot
                                .resolve("packs/spring-boot-4.1.2/publications/2.json")),
                latestSecond
                        .body()
                        .getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(
                requiredFields("publication-manifest.schema.json"),
                fieldNames(JSON
                        .readTree(latestSecond
                                .body()))
        );
    }

    @Test
    @DisplayName("BUILD_ONLY, projection 둘, 기대 번호 null, JSON 아닌 본문은 400 VALIDATION_FAILED 재시도 불가")
    void rejectsInvalidRequests() throws Exception {

        ObjectNode buildOnly = (ObjectNode) JSON
                .readTree(body(0));
        buildOnly
                .put(
                        "activationMode",
                        "BUILD_ONLY"
                );
        ObjectNode twoProjections = (ObjectNode) JSON
                .readTree(body(0));
        ((ArrayNode) twoProjections
                .get("projections"))
                .add(twoProjections
                        .get("projections")
                        .get(0)
                        .deepCopy());
        ObjectNode noExpected = (ObjectNode) JSON
                .readTree(body(0));
        ((ObjectNode) noExpected
                .get("projections")
                .get(0))
                .putNull("expectedPublicationSequence");

        for (String body : List
                .of(
                        buildOnly
                                .toString(),
                        twoProjections
                                .toString(),
                        noExpected
                                .toString(),
                        "not json"
                )) {
            error(
                    post(
                            SCHEDULER_TOKEN,
                            body
                    ),
                    400,
                    "VALIDATION_FAILED",
                    false
            );
        }

        assertEquals(
                0,
                buildCount()
        );
    }

    @Test
    @DisplayName("토큰 없음은 401과 WWW-Authenticate, operator의 POST와 모르는 경로는 403 계약 error, health는 토큰 없이 200")
    void guardsInternalPaths() throws Exception {

        HttpResponse<String> missing = send(HttpRequest
                .newBuilder(uri(PROCESSING_JOBS))
                .header(
                        "Content-Type",
                        "application/json"
                )
                .POST(HttpRequest.BodyPublishers
                        .ofString(body(0)))
                .build());

        error(
                missing,
                401,
                "AUTH_REQUIRED",
                false
        );
        assertEquals(
                "Bearer",
                missing
                        .headers()
                        .firstValue("WWW-Authenticate")
                        .orElseThrow()
        );
        error(
                post(
                        OPERATOR_TOKEN,
                        body(0)
                ),
                403,
                "FORBIDDEN",
                false
        );
        error(
                get(
                        SCHEDULER_TOKEN,
                        "/internal/v1/unknown"
                ),
                403,
                "FORBIDDEN",
                false
        );
        assertEquals(
                200,
                send(HttpRequest
                        .newBuilder(uri("/actuator/health"))
                        .build())
                        .statusCode()
        );
        assertEquals(
                0,
                buildCount()
        );
    }

    // 계약 error 본문의 상태, code, 재시도 여부, 필드 집합 확인 뒤 본문
    private static JsonNode error(
            HttpResponse<String> response,
            int status,
            String code,
            boolean retryable
    ) throws IOException {

        JsonNode error = JSON
                .readTree(response
                        .body());

        assertEquals(
                status,
                response
                        .statusCode(),
                response
                        .body()
        );
        assertEquals(
                code,
                error
                        .get("code")
                        .stringValue()
        );
        assertEquals(
                retryable,
                error
                        .get("retryable")
                        .booleanValue()
        );
        assertEquals(
                requiredFields("error"),
                fieldNames(error)
        );
        UUID
                .fromString(error
                        .get("traceId")
                        .stringValue());

        return error;
    }

    // 현재 처리 설정 hash와 기대 발행 번호의 processRequest
    private String body(long expected) {

        return CollectedRunFixtures
                .processRequest(
                        bundle,
                        BuildFixtures
                                .currentHash(),
                        expected
                );
    }

    // bearer 토큰으로 처리 요청 POST
    private HttpResponse<String> post(
            String token,
            String body
    ) throws IOException, InterruptedException {

        return send(HttpRequest
                .newBuilder(uri(PROCESSING_JOBS))
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .POST(HttpRequest.BodyPublishers
                        .ofString(body))
                .build());
    }

    // bearer 토큰으로 GET
    private HttpResponse<String> get(
            String token,
            String path
    ) throws IOException, InterruptedException {

        return send(HttpRequest
                .newBuilder(uri(path))
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .build());
    }

    // 요청 하나와 UTF-8 본문 응답
    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {

        return CLIENT
                .send(
                        request,
                        HttpResponse.BodyHandlers
                                .ofString(StandardCharsets.UTF_8)
                );
    }

    // 시험 서버 경로의 URI
    private URI uri(String path) {

        return URI
                .create("http://127.0.0.1:" + port + path);
    }

    // object root 아래 key 경로에 bytes 쓰기
    private static void write(
            String key,
            byte[] bytes
    ) throws IOException {

        Path file = objectRoot
                .resolve(key);
        Files
                .createDirectories(file
                        .getParent());
        Files
                .write(
                        file,
                        bytes
                );
    }

    // Build 행 수
    private int buildCount() {

        return jdbcClient
                .sql("SELECT count(*) FROM pipeline.builds")
                .query(Integer.class)
                .single();
    }

    // publication manifest 파일 수
    private static long manifestCount() throws IOException {

        try (Stream<Path> files = Files
                .walk(objectRoot
                        .resolve("packs/spring-boot-4.1.2/publications"))) {
            return files
                    .filter(Files::isRegularFile)
                    .count();
        }
    }

    // 게시된 Pack 파일 하나
    private static Path packFile() throws IOException {

        try (Stream<Path> files = Files
                .walk(objectRoot
                        .resolve("packs/spring-boot-4.1.2"))) {
            List<Path> packs = files
                    .filter(path -> path
                            .toString()
                            .endsWith(".pack"))
                    .toList();

            assertEquals(
                    1,
                    packs
                            .size()
            );

            return packs
                    .getFirst();
        }
    }

    // JSON 객체의 필드 이름 집합
    private static Set<String> fieldNames(JsonNode node) {

        return new HashSet<>(node
                .propertyNames());
    }

    // contracts 정의의 필수 필드, 정의 이름 또는 schema 파일 이름
    private static Set<String> requiredFields(String definition) throws IOException {

        Path contracts = Path
                .of(System
                        .getProperty("doclens.contractsDir"));
        JsonNode schema = definition
                .endsWith(".json") ? JSON
                        .readTree(contracts
                                .resolve(definition)
                                .toFile()) : JSON
                                        .readTree(contracts
                                                .resolve("collection.schema.json")
                                                .toFile())
                                        .get("$defs")
                                        .get(definition);
        Set<String> required = new HashSet<>();

        for (JsonNode field : schema
                .get("required")
                .values()) {
            required
                    .add(field
                            .stringValue());
        }

        return required;
    }
}
