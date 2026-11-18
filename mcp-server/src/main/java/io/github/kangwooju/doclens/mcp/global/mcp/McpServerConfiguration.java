package io.github.kangwooju.doclens.mcp.global.mcp;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import tools.jackson.databind.json.JsonMapper;

/**
 * STDIO MCP 서버 구성
 *
 * <p>SDK 입력 검증은 끄고 Tool 진입부에서 닫힌 오류 코드로 응답(D16)
 */
@Configuration
public class McpServerConfiguration {

    /** MCP 클라이언트에 보이는 서버 이름 */
    public static final String SERVER_NAME = "doclens";

    @Bean
    McpJsonMapper mcpJsonMapper() {

        return new JacksonMcpJsonMapper(JsonMapper
                .builder()
                .build());
    }

    @Bean
    ServerIdentity serverIdentity(ObjectProvider<BuildProperties> buildProperties) {

        BuildProperties build = buildProperties
                .getIfAvailable();

        return new ServerIdentity(
                SERVER_NAME,
                build == null
                        ? "dev"
                        : build
                                .getVersion(),
                PinnedProtocolStdioTransport.PROTOCOL_VERSION
        );
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(
            ProtocolStreams streams,
            McpJsonMapper jsonMapper,
            ServerIdentity identity,
            List<McpToolEndpoint> tools
    ) {

        return McpServer
                .sync(new PinnedProtocolStdioTransport(
                        jsonMapper,
                        streams
                                .input(),
                        streams
                                .output()
                ))
                .serverInfo(
                        identity
                                .name(),
                        identity
                                .programVersion()
                )
                .capabilities(ServerCapabilities
                        .builder()
                        .tools(false)
                        .build())
                .jsonMapper(jsonMapper)
                .validateToolInputs(false)
                .tools(tools
                        .stream()
                        .map(McpToolEndpoint::specification)
                        .toList())
                .build();
    }
}
