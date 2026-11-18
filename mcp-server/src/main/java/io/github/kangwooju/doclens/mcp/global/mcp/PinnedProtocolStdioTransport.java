package io.github.kangwooju.doclens.mcp.global.mcp;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.ProtocolVersions;

/**
 * 프로토콜 버전을 2025-06-18 하나로 고정한 STDIO transport(D16 응답 계약 기준)
 *
 * <p>세션 transport를 {@link BudgetFinalizingTransport}로 감싸 실제 id 기준 예산 확정과 종료 신호 전달
 */
public final class PinnedProtocolStdioTransport extends StdioServerTransportProvider {

    /** 지원 MCP 프로토콜 버전 */
    public static final String PROTOCOL_VERSION = ProtocolVersions.MCP_2025_06_18;

    private final ToolResponses toolResponses;

    private final Runnable onSessionClosed;

    public PinnedProtocolStdioTransport(
            McpJsonMapper jsonMapper,
            ProtocolStreams streams,
            ToolResponses toolResponses
    ) {

        super(
                jsonMapper,
                streams
                        .input(),
                streams
                        .output()
        );
        this.toolResponses = toolResponses;
        this.onSessionClosed = streams
                .onSessionClosed();
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {

        super
                .setSessionFactory(transport -> sessionFactory
                        .create(new BudgetFinalizingTransport(
                                transport,
                                toolResponses,
                                onSessionClosed
                        )));
    }

    @Override
    public List<String> protocolVersions() {

        return List
                .of(PROTOCOL_VERSION);
    }
}
