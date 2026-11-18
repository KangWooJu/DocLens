package io.github.kangwooju.doclens.mcp.global.mcp;

/**
 * MCP 서버 이름·프로그램 버전·프로토콜 버전
 */
public record ServerIdentity(
        String name,
        String programVersion,
        String protocolVersion
) {
}
