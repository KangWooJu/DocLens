package io.github.kangwooju.doclens.mcp.global.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * 입력 끝(EOF·읽기 실패·닫기·세션 종료) 신호를 내는 stdin 래퍼
 *
 * <p>MCP 클라이언트가 stdin을 닫으면 서버 종료 시작
 */
public final class EndOfInputSignal extends FilterInputStream {

    private final CountDownLatch ended = new CountDownLatch(1);

    public EndOfInputSignal(InputStream input) {

        super(input);
    }

    @Override
    public int read() throws IOException {

        try {
            return signalOnEnd(super
                    .read());
        } catch (IOException exception) {
            ended
                    .countDown();

            throw exception;
        }
    }

    @Override
    public int read(
            byte[] buffer,
            int offset,
            int length
    ) throws IOException {

        try {
            return signalOnEnd(super
                    .read(
                            buffer,
                            offset,
                            length
                    ));
        } catch (IOException exception) {
            ended
                    .countDown();

            throw exception;
        }
    }

    @Override
    public void close() throws IOException {

        ended
                .countDown();
        super
                .close();
    }

    /** 외부 종료 신호(SDK 세션 종료 등) */
    public void signalEnd() {

        ended
                .countDown();
    }

    /** 입력 끝까지 대기, 인터럽트도 끝으로 처리 */
    public void awaitEnd() {

        try {
            ended
                    .await();
        } catch (InterruptedException exception) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

    // 읽은 값이 -1이면 끝 신호
    private int signalOnEnd(int result) {

        if (result < 0) {
            ended
                    .countDown();
        }

        return result;
    }
}
