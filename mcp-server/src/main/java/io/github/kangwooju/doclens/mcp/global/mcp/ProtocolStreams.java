package io.github.kangwooju.doclens.mcp.global.mcp;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * MCP JSON-RPC 입출력 스트림
 *
 * <p>output은 프로세스의 실제 stdout, 프로토콜 메시지 외 출력 금지
 */
public record ProtocolStreams(
        InputStream input,
        OutputStream output
) {
}
