package io.github.kangwooju.doclens.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 실제 socket을 여는 가짜 수신자
 * 받은 요청을 모두 기록하고, 응답 지연과 연결 끊기를 흉내 냄
 */
public final class FakeHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors
            .newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Function<Request, Reply> responder = request -> Reply
            .of(
                    404,
                    null
            );

    /** loopback 빈 port에 서버 시작 */
    public FakeHttpServer() throws IOException {

        server = HttpServer
                .create(
                        new InetSocketAddress(
                                InetAddress
                                        .getLoopbackAddress(),
                                0
                        ),
                        0
                );
        server
                .setExecutor(executor);
        server
                .createContext(
                        "/",
                        this::handle
                );
        server
                .start();
    }

    /** 서버 주소 */
    public URI uri() {

        return URI
                .create("http://127.0.0.1:" + server
                        .getAddress()
                        .getPort());
    }

    /** 요청별 응답 함수 교체 */
    public void respond(Function<Request, Reply> responder) {

        this.responder = responder;
    }

    /** 받은 요청 전체 */
    public List<Request> requests() {

        return List
                .copyOf(requests);
    }

    /** 경로가 같은 요청 */
    public List<Request> requests(
            String method,
            String pathPrefix
    ) {

        return requests
                .stream()
                .filter(request -> request
                        .method()
                        .equals(method)
                        && request
                                .path()
                                .startsWith(pathPrefix))
                .toList();
    }

    /** 동시에 처리 중이던 요청 수의 최댓값 */
    public int maxInFlight() {

        return maxInFlight
                .get();
    }

    /** 기록과 동시 처리 최댓값 비우기 */
    public void clear() {

        requests
                .clear();
        maxInFlight
                .set(0);
    }

    /** 서버 정지 */
    @Override
    public void close() {

        server
                .stop(0);
        executor
                .shutdownNow();
    }

    // 요청 기록, 응답 함수 호출, 지연과 연결 끊기 적용
    private void handle(HttpExchange exchange) throws IOException {

        int current = inFlight
                .incrementAndGet();
        maxInFlight
                .accumulateAndGet(
                        current,
                        Math::max
                );

        try {
            Request request = new Request(
                    exchange
                            .getRequestMethod(),
                    exchange
                            .getRequestURI()
                            .getPath(),
                    exchange
                            .getRequestURI()
                            .getQuery(),
                    exchange
                            .getRequestHeaders()
                            .getFirst("Authorization"),
                    exchange
                            .getRequestHeaders()
                            .getFirst("Content-Type"),
                    new String(
                            exchange
                                    .getRequestBody()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8
                    )
            );
            requests
                    .add(request);
            Reply reply = responder
                    .apply(request);

            if (reply
                    .delayMillis() > 0) {
                Thread
                        .sleep(reply
                                .delayMillis());
            }

            if (reply
                    .drop()) {
                throw new IOException("fake receiver drops the connection after processing");
            }

            byte[] body = reply
                    .body() == null ? new byte[0] : reply
                            .body()
                            .getBytes(StandardCharsets.UTF_8);
            exchange
                    .getResponseHeaders()
                    .add(
                            "Content-Type",
                            "application/json"
                    );
            exchange
                    .sendResponseHeaders(
                            reply
                                    .status(),
                            body.length == 0 ? -1 : body.length
                    );

            try (OutputStream output = exchange
                    .getResponseBody()) {
                output
                        .write(body);
            }
        } catch (InterruptedException exception) {
            Thread
                    .currentThread()
                    .interrupt();
        } finally {
            inFlight
                    .decrementAndGet();
            exchange
                    .close();
        }
    }

    /** 받은 요청 하나 */
    public record Request(
            String method,
            String path,
            String query,
            String authorization,
            String contentType,
            String body
    ) {
    }

    /** 보낼 응답, drop이면 응답 없이 연결 끊기 */
    public record Reply(
            int status,
            String body,
            long delayMillis,
            boolean drop
    ) {

        /** 상태와 본문 */
        public static Reply of(
                int status,
                String body
        ) {

            return new Reply(
                    status,
                    body,
                    0,
                    false
            );
        }

        /** 늦게 보내는 응답 */
        public static Reply delayed(
                long delayMillis,
                int status,
                String body
        ) {

            return new Reply(
                    status,
                    body,
                    delayMillis,
                    false
            );
        }

        /** 처리 뒤 응답 없이 연결 끊기 */
        public static Reply dropped() {

            return new Reply(
                    0,
                    null,
                    0,
                    true
            );
        }
    }
}
