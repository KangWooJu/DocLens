package io.github.kangwooju.doclens.mcp.global.mcp;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * MCP JSON-RPC 입출력 스트림과 세션 종료 시 실행할 동작
 *
 * <p>output은 프로세스의 실제 stdout, 프로토콜 메시지 외 출력 금지
 * <p>onSessionClosed는 SDK가 세션을 닫을 때(stdin EOF, 해석 불가 입력 등) 실행
 */
public record ProtocolStreams(
        InputStream input,
        OutputStream output,
        Runnable onSessionClosed
) {
}
