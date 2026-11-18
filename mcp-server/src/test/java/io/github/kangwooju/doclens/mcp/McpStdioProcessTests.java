package io.github.kangwooju.doclens.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.kangwooju.doclens.core.pack.TestPacks;
import io.github.kangwooju.doclens.mcp.pack.PackInstalls;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 자식 JVM으로 띄운 서버의 stdout·종료 동작 시험
 */
@DisplayName("STDIO MCP 프로세스")
class McpStdioProcessTests {

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .build();

    private static final long RESPONSE_TIMEOUT_SECONDS = 30;

    @TempDir
    Path cacheRoot;

    private Process process;

    private final List<String> stdoutLines = new ArrayList<>();

    @AfterEach
    void stopProcess() {

        if (process != null && process
                .isAlive()) {
            process
                    .destroyForcibly();
        }
    }

    @Test
    @DisplayName("JSON-RPC만 쓰는 stdout, get_status 예산 상한, 파일 로그, stdin 종료 시 정상 종료")
    void servesGetStatusOverStdio() throws Exception {

        process = start();
        BufferedReader stdout = new BufferedReader(new InputStreamReader(
                process
                        .getInputStream(),
                StandardCharsets.UTF_8
        ));
        OutputStream stdin = process
                .getOutputStream();

        JsonNode initialize = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"
        );
        assertEquals(
                "2025-06-18",
                initialize
                        .at("/result/protocolVersion")
                        .asString()
        );
        assertEquals(
                "doclens",
                initialize
                        .at("/result/serverInfo/name")
                        .asString()
        );

        send(
                stdin,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        );

        JsonNode tools = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"
        );
        assertEquals(
                Set
                        .of(
                                "get_status",
                                "list_projects",
                                "list_versions",
                                "search_docs"
                        ),
                tools
                        .at("/result/tools")
                        .valueStream()
                        .map(tool -> tool
                                .at("/name")
                                .asString())
                        .collect(Collectors
                                .toSet())
        );
        assertTrue(tools
                .at("/result/tools")
                .valueStream()
                .allMatch(tool -> tool
                        .at("/annotations/readOnlyHint")
                        .asBoolean()));

        JsonNode status = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"get_status\",\"arguments\":{}}}"
        );
        String statusLine = stdoutLines
                .getLast();
        JsonNode payload = status
                .at("/result/structuredContent");

        assertFalse(status
                .at("/result/isError")
                .asBoolean());
        assertEquals(
                "External source data.",
                status
                        .at("/result/content/0/text")
                        .asString()
        );
        assertEquals(
                "OK",
                payload
                        .at("/status")
                        .asString()
        );
        assertEquals(
                "NOT_READY",
                payload
                        .at("/readiness")
                        .asString()
        );
        assertEquals(
                "STATUS",
                payload
                        .at("/results/0/kind")
                        .asString()
        );
        assertEquals(
                "2025-06-18",
                payload
                        .at("/results/0/attributes/protocolVersion")
                        .asString()
        );

        long reportedWireBytes = payload
                .at("/budget/wireBytes")
                .asLong();
        long actualWireBytes = statusLine
                .getBytes(StandardCharsets.UTF_8).length + 1L;

        assertEquals(
                actualWireBytes,
                reportedWireBytes,
                "reported wireBytes must equal the real stdout line"
        );
        assertEquals(
                (reportedWireBytes + 2) / 3,
                payload
                        .at("/budget/estimatedTokens")
                        .asLong()
        );
        assertTrue(payload
                .at("/budget/estimatedTokens")
                .asLong() <= 4096);

        JsonNode atMinimum = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"get_status\",\"arguments\":{\"maxContextTokens\":256}}}"
        );
        JsonNode minimum = atMinimum
                .at("/result/structuredContent");

        assertFalse(
                atMinimum
                        .at("/result/isError")
                        .asBoolean(),
                "get_status must fit the minimum budget with an integer id"
        );
        assertTrue(minimum
                .at("/budget/estimatedTokens")
                .asLong() <= 256);
        assertEquals(
                stdoutLines
                        .getLast()
                        .getBytes(StandardCharsets.UTF_8).length + 1L,
                minimum
                        .at("/budget/wireBytes")
                        .asLong()
        );

        JsonNode invalid = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"get_status\",\"arguments\":{\"maxContextTokens\":\"many\"}}}"
        );
        assertTrue(invalid
                .at("/result/isError")
                .asBoolean());
        assertEquals(
                "INVALID_INPUT",
                invalid
                        .at("/result/structuredContent/code")
                        .asString()
        );

        stdin
                .close();
        assertTrue(
                process
                        .waitFor(
                                RESPONSE_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        ),
                "server must exit after stdin closes"
        );
        assertEquals(
                0,
                process
                        .exitValue()
        );

        String rest;

        while ((rest = stdout
                .readLine()) != null) {
            stdoutLines
                    .add(rest);
        }

        for (String line : stdoutLines) {
            assertEquals(
                    "2.0",
                    JSON
                            .readTree(line)
                            .at("/jsonrpc")
                            .asString(),
                    "stdout line must be JSON-RPC"
            );
        }

        Path log = cacheRoot
                .resolve("logs/server.log");
        assertTrue(Files
                .isRegularFile(log));
        assertTrue(Files
                .size(log) > 0);
    }

    @Test
    @DisplayName("해석 불가 입력 줄의 세션 종료와 프로세스 정상 종료(SDK 2.1.0 제약, java-sdk PR #1173 병합 시 변경)")
    void malformedLineEndsSessionAndProcess() throws Exception {

        process = start();
        BufferedReader stdout = new BufferedReader(new InputStreamReader(
                process
                        .getInputStream(),
                StandardCharsets.UTF_8
        ));
        OutputStream stdin = process
                .getOutputStream();

        request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"
        );
        send(
                stdin,
                "{not json"
        );

        assertTrue(
                process
                        .waitFor(
                                RESPONSE_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        ),
                "server must exit after the SDK closes the session"
        );
        assertEquals(
                0,
                process
                        .exitValue()
        );
        assertEquals(
                null,
                stdout
                        .readLine(),
                "no parse error response is written by SDK 2.1.0"
        );
    }

    @Test
    @DisplayName("설치 Pack의 get_status·list_projects·list_versions·search_docs 응답과 정확한 wireBytes")
    void servesInstalledPacks() throws Exception {

        PackInstalls
                .install(
                        cacheRoot,
                        TestPacks
                                .builder()
                                .document("overview")
                                .chunk("Spring Boot overview")
                                .chunk(
                                        "Configure the server port",
                                        List
                                                .of("server.port")
                                )
                );
        process = start();
        BufferedReader stdout = new BufferedReader(new InputStreamReader(
                process
                        .getInputStream(),
                StandardCharsets.UTF_8
        ));
        OutputStream stdin = process
                .getOutputStream();

        initialize(
                stdin,
                stdout
        );

        JsonNode status = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"get_status\",\"arguments\":{}}}"
        )
                .at("/result/structuredContent");

        assertEquals(
                "READY_STALE",
                status
                        .at("/readiness")
                        .asString()
        );
        assertEquals(
                "STALE_SOURCE",
                status
                        .at("/results/0/attributes/updateStatus")
                        .asString()
        );
        assertEquals(
                "spring-boot-4.0.0-test",
                status
                        .at("/results/0/attributes/installedBuilds/0/packId")
                        .asString()
        );
        assertEquals(
                "BM25",
                status
                        .at("/results/0/attributes/capabilities/0")
                        .asString()
        );

        JsonNode projects = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"list_projects\",\"arguments\":{}}}"
        )
                .at("/result/structuredContent");

        assertEquals(
                "PROJECT",
                projects
                        .at("/results/0/kind")
                        .asString()
        );
        assertEquals(
                "spring-boot",
                projects
                        .at("/results/0/attributes/projectId")
                        .asString()
        );

        JsonNode versions = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":\"versions-1\",\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"list_versions\",\"arguments\":{\"project\":\"spring-boot\"}}}"
        )
                .at("/result/structuredContent");

        assertEquals(
                "4.0.0",
                versions
                        .at("/results/0/attributes/exactVersion")
                        .asString()
        );
        assertTrue(versions
                .at("/results/0/attributes/checksum")
                .asString()
                .startsWith("sha256:"));
        assertEquals(
                stdoutLines
                        .getLast()
                        .getBytes(StandardCharsets.UTF_8).length + 1L,
                versions
                        .at("/budget/wireBytes")
                        .asLong(),
                "reported wireBytes must equal the real stdout line for a string id"
        );

        JsonNode unknown = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"list_versions\",\"arguments\":{\"project\":\"spring-data\"}}}"
        );

        assertEquals(
                "NOT_FOUND",
                unknown
                        .at("/result/structuredContent/code")
                        .asString()
        );

        JsonNode search = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"search_docs\",\"arguments\":{\"query\":\"overview server.port\","
                        + "\"project\":\"spring-boot\",\"exactVersion\":\"4.0.0\"}}}"
        )
                .at("/result/structuredContent");

        assertEquals(
                "Configure the server port",
                search
                        .at("/results/0/text")
                        .asString()
        );
        assertTrue(search
                .at("/results/0/attributes/exactIdentifierMatch")
                .asBoolean());
        assertEquals(
                "Spring Boot overview",
                search
                        .at("/results/1/text")
                        .asString()
        );
        assertEquals(
                "spring-boot-4.0.0-test",
                search
                        .at("/resolved/packId")
                        .asString()
        );
        assertEquals(
                search
                        .at("/resolved/checksum")
                        .asString(),
                search
                        .at("/results/0/citation/checksum")
                        .asString()
        );
        assertEquals(
                stdoutLines
                        .getLast()
                        .getBytes(StandardCharsets.UTF_8).length + 1L,
                search
                        .at("/budget/wireBytes")
                        .asLong()
        );

        String longQuery = "가"
                .repeat(700);
        JsonNode tooLong = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"search_docs\",\"arguments\":{\"query\":\"" + longQuery + "\","
                        + "\"project\":\"spring-boot\",\"exactVersion\":\"4.0.0\"}}}"
        );

        assertEquals(
                "QUERY_TOO_LONG",
                tooLong
                        .at("/result/structuredContent/code")
                        .asString()
        );

        JsonNode withCursor = request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"search_docs\",\"arguments\":{\"query\":\"spring\","
                        + "\"project\":\"spring-boot\",\"exactVersion\":\"4.0.0\",\"cursor\":\"x\"}}}"
        );

        assertEquals(
                "INVALID_INPUT",
                withCursor
                        .at("/result/structuredContent/code")
                        .asString()
        );

        stdin
                .close();
        assertTrue(process
                .waitFor(
                        RESPONSE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                ));
    }

    // initialize 요청과 initialized 알림
    private void initialize(
            OutputStream stdin,
            BufferedReader stdout
    ) throws Exception {

        request(
                stdin,
                stdout,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"
        );
        send(
                stdin,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        );
    }

    // 캐시 루트를 임시 디렉터리로 지정한 서버 자식 프로세스
    private Process start() throws IOException {

        String classpath = System
                .getProperty("doclens.test.serverClasspath");
        assertNotNull(
                classpath,
                "doclens.test.serverClasspath is set by the Gradle test task"
        );
        String java = ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java");

        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-cp",
                classpath,
                McpServerApplication.class
                        .getName()
        );
        builder
                .environment()
                .put(
                        "DOCLENS_CACHE",
                        cacheRoot
                                .toString()
                );
        builder
                .redirectError(ProcessBuilder.Redirect.DISCARD);

        return builder
                .start();
    }

    // 요청 한 줄 전송 뒤 같은 id의 응답 한 줄
    private JsonNode request(
            OutputStream stdin,
            BufferedReader stdout,
            String message
    ) throws Exception {

        send(
                stdin,
                message
        );
        String line = CompletableFuture
                .supplyAsync(() -> readLine(stdout))
                .get(
                        RESPONSE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
        assertNotNull(
                line,
                "server closed stdout before responding"
        );
        stdoutLines
                .add(line);

        return JSON
                .readTree(line);
    }

    // 요청 한 줄(LF 종료) 전송
    private static void send(
            OutputStream stdin,
            String message
    ) throws IOException {

        stdin
                .write((message + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        stdin
                .flush();
    }

    // stdout 한 줄 읽기
    private static String readLine(BufferedReader stdout) {

        try {
            return stdout
                    .readLine();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
