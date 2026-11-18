package io.github.kangwooju.doclens.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.kangwooju.doclens.mcp.global.mcp.ProtocolStreams;
import io.github.kangwooju.doclens.mcp.global.mcp.ServerIdentity;
import io.github.kangwooju.doclens.mcp.status.tool.GetStatusTool;
import io.modelcontextprotocol.server.McpSyncServer;

@SpringBootTest
@DisplayName("McpServerApplication")
class McpServerApplicationTests {

    @Autowired
    McpSyncServer mcpServer;

    @Autowired
    GetStatusTool getStatusTool;

    @Autowired
    ServerIdentity serverIdentity;

    @Test
    @DisplayName("스프링 애플리케이션 컨텍스트 로딩과 MCP 서버·get_status 등록")
    void contextLoads() {

        assertNotNull(mcpServer);
        assertEquals(
                GetStatusTool.NAME,
                getStatusTool
                        .specification()
                        .tool()
                        .name()
        );
        assertEquals(
                "2025-06-18",
                serverIdentity
                        .protocolVersion()
        );
    }

    @TestConfiguration
    static class TestStreams {

        // 빈 stdin과 메모리 stdout으로 만든 시험용 프로토콜 스트림
        @Bean
        ProtocolStreams protocolStreams() {

            return new ProtocolStreams(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    () -> {
                    }
            );
        }
    }
}
