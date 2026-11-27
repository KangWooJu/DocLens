package io.github.kangwooju.doclens.mcp.global.mcp;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;

/**
 * 세션 transport 래퍼
 * 전송 직전 실제 id 기준 예산 재계산과 세션 종료 신호 전달
 */
final class BudgetFinalizingTransport implements McpServerTransport {

    private final McpServerTransport delegate;

    private final ToolResponses toolResponses;

    private final Runnable onClosed;

    // 원래 transport와 종료 동작을 받는 래퍼
    BudgetFinalizingTransport(
            McpServerTransport delegate,
            ToolResponses toolResponses,
            Runnable onClosed
    ) {

        this.delegate = delegate;
        this.toolResponses = toolResponses;
        this.onClosed = onClosed;
    }

    /** 예산 확정 후 메시지 전송 */
    @Override
    public Mono<Void> sendMessage(JSONRPCMessage message) {

        return delegate
                .sendMessage(finalized(message));
    }

    /** 정상 종료 후 종료 신호 */
    @Override
    public Mono<Void> closeGracefully() {

        return delegate
                .closeGracefully()
                .doFinally(signal -> onClosed
                        .run());
    }

    /** 즉시 종료 후 종료 신호 */
    @Override
    public void close() {

        try {
            delegate
                    .close();
        } finally {
            onClosed
                    .run();
        }
    }

    /** 원래 transport에 역직렬화 위임 */
    @Override
    public <T> T unmarshalFrom(
            Object data,
            TypeRef<T> typeRef
    ) {

        return delegate
                .unmarshalFrom(
                        data,
                        typeRef
                );
    }

    /** 원래 transport의 프로토콜 버전 */
    @Override
    public List<String> protocolVersions() {

        return delegate
                .protocolVersions();
    }

    // Tool 결과 응답이면 실제 id로 예산을 다시 맞춘 응답
    private JSONRPCMessage finalized(JSONRPCMessage message) {

        if (message instanceof JSONRPCResponse response
                && response
                        .result() instanceof CallToolResult result) {
            return JSONRPCResponse
                    .result(
                            response
                                    .id(),
                            toolResponses
                                    .finalizeFor(
                                            result,
                                            response
                                                    .id()
                                    )
                    );
        }

        return message;
    }
}
