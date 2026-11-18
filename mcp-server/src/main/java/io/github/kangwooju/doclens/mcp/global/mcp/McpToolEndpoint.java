package io.github.kangwooju.doclens.mcp.global.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * MCP Tool 진입부, 구현 bean은 서버 시작 시 Tool 목록에 등록
 */
public interface McpToolEndpoint {

    /** Tool 정의와 호출 처리 */
    SyncToolSpecification specification();
}
