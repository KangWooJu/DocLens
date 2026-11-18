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
 *
 * <p>Tool 응답 전송 직전 실제 JSON-RPC id 기준 예산 재계산
 * <p>세션 종료(stdin EOF·해석 불가 입력 등) 시 프로세스 종료 신호
 */
final class BudgetFinalizingTransport implements McpServerTransport {

    private final McpServerTransport delegate;

    private final ToolResponses toolResponses;

    private final Runnable onClosed;

    BudgetFinalizingTransport(
            McpServerTransport delegate,
            ToolResponses toolResponses,
            Runnable onClosed
    ) {

        this.delegate = delegate;
        this.toolResponses = toolResponses;
        this.onClosed = onClosed;
    }

    @Override
    public Mono<Void> sendMessage(JSONRPCMessage message) {

        return delegate
                .sendMessage(finalized(message));
    }

    @Override
    public Mono<Void> closeGracefully() {

        return delegate
                .closeGracefully()
                .doFinally(signal -> onClosed
                        .run());
    }

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
