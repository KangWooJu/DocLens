package io.github.kangwooju.doclens.mcp.global.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;

/**
 * 프로토콜 버전을 2025-06-18 하나로 고정한 STDIO transport(D16 응답 계약 기준)
 */
public final class PinnedProtocolStdioTransport extends StdioServerTransportProvider {

    /** 지원 MCP 프로토콜 버전 */
    public static final String PROTOCOL_VERSION = ProtocolVersions.MCP_2025_06_18;

    public PinnedProtocolStdioTransport(
            McpJsonMapper jsonMapper,
            InputStream input,
            OutputStream output
    ) {

        super(
                jsonMapper,
                input,
                output
        );
    }

    @Override
    public List<String> protocolVersions() {

        return List
                .of(PROTOCOL_VERSION);
    }
}
