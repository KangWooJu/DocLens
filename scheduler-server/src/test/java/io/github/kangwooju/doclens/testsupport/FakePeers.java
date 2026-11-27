package io.github.kangwooju.doclens.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Reply;
import io.github.kangwooju.doclens.testsupport.FakeHttpServer.Request;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가짜 collector-api와 가짜 pipeline
 * collector는 requestId별 job 하나, pipeline은 발행 번호와 처리 응답 대기열
 */
public final class FakePeers implements AutoCloseable {

    /** 시험 수신자 토큰 */
    public static final String TOKEN = "test-scheduler-token";

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    private static final String JOBS = "/internal/v1/collection-jobs";

    private final FakeHttpServer collector;
    private final FakeHttpServer pipeline;
    private final String readyRun;
    private final Map<String, UUID> jobsByRequest = new ConcurrentHashMap<>();
    private final Map<UUID, String> jobStates = new ConcurrentHashMap<>();
    private final Deque<Reply> submitReplies = new ConcurrentLinkedDeque<>();
    private final Deque<Reply> processReplies = new ConcurrentLinkedDeque<>();
    private volatile String servedReadyRun;
    private volatile Long latestSequence;

    /** 두 가짜 서버 시작, readyRun은 계약 예시 bytes */
    public FakePeers() throws IOException {

        collector = new FakeHttpServer();
        pipeline = new FakeHttpServer();
        readyRun = Files
                .readString(contractsExample("root100/ready-run.json"));
        reset();
        collector
                .respond(this::collectorReply);
        pipeline
                .respond(this::pipelineReply);
    }

    /** 계약 예시 파일 경로 */
    public static Path contractsExample(String name) {

        return Path
                .of(
                        System
                                .getProperty("doclens.contractsDir"),
                        "examples",
                        name
                );
    }

    /** 계약 예시 collectionRequest를 복사한 요청 틀 임시 파일 경로 */
    public static String requestTemplateFile() {

        try {
            Path file = Files
                    .createTempFile(
                            "collection-request",
                            ".json"
                    );
            Files
                    .copy(
                            contractsExample("collectionRequest.accepted.json"),
                            file,
                            StandardCopyOption.REPLACE_EXISTING
                    );

            return file
                    .toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 가짜 collector-api */
    public FakeHttpServer collector() {

        return collector;
    }

    /** 가짜 pipeline */
    public FakeHttpServer pipeline() {

        return pipeline;
    }

    /** job, 대기 응답, 발행 번호, 기록 초기화 */
    public void reset() {

        jobsByRequest
                .clear();
        jobStates
                .clear();
        submitReplies
                .clear();
        processReplies
                .clear();
        servedReadyRun = readyRun;
        latestSequence = null;
        collector
                .clear();
        pipeline
                .clear();
    }

    /** job 상태 바꾸기 */
    public void setJobState(
            UUID jobId,
            String state
    ) {

        jobStates
                .put(
                        jobId,
                        state
                );
    }

    /** 다음 접수 요청 하나에 줄 응답 */
    public void nextSubmit(Reply reply) {

        submitReplies
                .add(reply);
    }

    /** 다음 처리 요청 하나에 줄 응답 */
    public void nextProcess(Reply reply) {

        processReplies
                .add(reply);
    }

    /** 최신 발행 번호, null이면 404 */
    public void setLatestSequence(Long sequence) {

        latestSequence = sequence;
    }

    /** readyRun 조회가 돌려줄 bytes 바꾸기 */
    public void serveReadyRun(String body) {

        servedReadyRun = body;
    }

    /** 계약 error 본문 */
    public static String error(
            String code,
            boolean retryable
    ) {

        return """
                {"schemaVersion":1,"code":"%s","message":"fake %s","requestId":null,"retryable":%s,"traceId":"%s"}
                """
                .formatted(
                        code,
                        code,
                        retryable,
                        UUID
                                .randomUUID()
                );
    }

    /** 처리 성공 receipt 본문 */
    public static String receipt() {

        return """
                {"runInputSnapshotHash":"sha256:%s","projectionResults":[{"packScope":{
                "project":"spring-boot","exactVersion":"4.0.0","applicability":"EXACT_RELEASE"},
                "state":"SUCCEEDED","buildId":"%s","buildJobId":"%s","activationJobId":null}],
                "resultObjectRef":{"objectKey":"packs/p/publications/1.json","contentHash":"sha256:%s",
                "sizeBytes":123}}
                """
                .formatted(
                        "a"
                                .repeat(64),
                        UUID
                                .randomUUID(),
                        UUID
                                .randomUUID(),
                        "b"
                                .repeat(64)
                );
    }

    /** 두 서버 정지 */
    @Override
    public void close() {

        collector
                .close();
        pipeline
                .close();
    }

    // collector 접수, 상태, readyRun 응답
    private Reply collectorReply(Request request) {

        if (request
                .method()
                .equals("POST")) {
            Reply scripted = submitReplies
                    .poll();

            return scripted != null ? scripted : accept(request);
        }

        String path = request
                .path();

        if (path
                .endsWith("/ready-run")) {
            return Reply
                    .of(
                            200,
                            servedReadyRun
                    );
        }

        UUID jobId = UUID
                .fromString(path
                        .substring(JOBS
                                .length() + 1));
        String state = jobStates
                .get(jobId);

        if (state == null) {
            return Reply
                    .of(
                            404,
                            error(
                                    "NOT_FOUND",
                                    false
                            )
                    );
        }

        String result = state
                .equals("SUCCEEDED") ? readyRunRef() : "null";

        return Reply
                .of(
                        200,
                        """
                                {"schemaVersion":1,"requestId":"%s","jobId":"%s","state":"%s","waitReason":null,
                                "attemptCount":1,"leaseEpoch":1,"nextAttemptAt":null,"updatedAt":"2026-11-27T00:00:00Z",
                                "result":%s,"lastErrorCode":null}
                                """
                                .formatted(
                                        UUID
                                                .randomUUID(),
                                        jobId,
                                        state,
                                        result
                                )
                );
    }

    // requestId마다 job 하나, 같은 requestId 재전송은 200 재생
    private Reply accept(Request request) {

        String requestId = JSON_MAPPER
                .readTree(request
                        .body())
                .get("requestId")
                .stringValue();
        boolean duplicate = jobsByRequest
                .containsKey(requestId);
        UUID jobId = jobsByRequest
                .computeIfAbsent(
                        requestId,
                        key -> UUID
                                .randomUUID()
                );
        jobStates
                .putIfAbsent(
                        jobId,
                        "QUEUED"
                );

        return Reply
                .of(
                        duplicate ? 200 : 202,
                        """
                                {"schemaVersion":1,"requestId":"%s","jobId":"%s","requestHash":"sha256:%s",
                                "state":"QUEUED","duplicate":%s,"acceptedAt":"2026-11-27T00:00:00Z"}
                                """
                                .formatted(
                                        requestId,
                                        jobId,
                                        "a"
                                                .repeat(64),
                                        duplicate
                                )
                );
    }

    // pipeline 최신 발행 조회와 처리 응답
    private Reply pipelineReply(Request request) {

        if (request
                .method()
                .equals("GET")) {
            Long sequence = latestSequence;

            return sequence == null ? Reply
                    .of(
                            404,
                            error(
                                    "NOT_FOUND",
                                    false
                            )
                    ) : Reply
                            .of(
                                    200,
                                    """
                                            {"schemaVersion":1,"publicationSequence":%d}
                                            """
                                            .formatted(sequence)
                            );
        }

        Reply scripted = processReplies
                .poll();

        return scripted != null ? scripted : Reply
                .of(
                        200,
                        receipt()
                );
    }

    // 계약 예시 readyRun bytes의 objectRef
    private String readyRunRef() {

        byte[] bytes = readyRun
                .getBytes(StandardCharsets.UTF_8);

        try {
            return """
                    {"objectKey":"manifests/run/ready-run.json","contentHash":"sha256:%s","sizeBytes":%d}
                    """
                    .formatted(
                            HexFormat
                                    .of()
                                    .formatHex(MessageDigest
                                            .getInstance("SHA-256")
                                            .digest(bytes)),
                            bytes.length
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new UncheckedIOException(new IOException(exception));
        }
    }
}
