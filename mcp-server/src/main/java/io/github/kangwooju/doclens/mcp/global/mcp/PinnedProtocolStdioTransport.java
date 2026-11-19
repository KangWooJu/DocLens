package io.github.kangwooju.doclens.mcp.global.mcp;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.mcp.dto.ProtocolStreams;
import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.ProtocolVersions;

/** 프로토콜 버전을 2025-06-18로 고정한 STDIO transport */
public final class PinnedProtocolStdioTransport extends StdioServerTransportProvider {

    /** 지원 MCP 프로토콜 버전 */
    public static final String PROTOCOL_VERSION = ProtocolVersions.MCP_2025_06_18;

    private final ToolResponses toolResponses;

    private final Runnable onSessionClosed;

    /** 프로토콜 스트림 기반 transport 생성 */
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

    /** 세션 transport를 예산 확정 래퍼로 교체 */
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

    /** 지원 프로토콜 버전 목록 */
    @Override
    public List<String> protocolVersions() {

        return List
                .of(PROTOCOL_VERSION);
    }
}
