package io.github.kangwooju.doclens.mcp.global.mcp.dto;

import java.io.InputStream;
import java.io.OutputStream;

/** MCP 입출력 스트림과 세션 종료 시 실행할 동작 */
public record ProtocolStreams(
        InputStream input,
        OutputStream output,
        Runnable onSessionClosed
) {
}
