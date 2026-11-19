package io.github.kangwooju.doclens.mcp.global.mcp.dto;

/** MCP 서버 이름과 버전 정보 */
public record ServerIdentity(
        String name,
        String programVersion,
        String protocolVersion
) {
}
