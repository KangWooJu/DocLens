package io.github.kangwooju.doclens.mcp.global.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/** stdin 래퍼, 입력이 끝나면 서버 종료 신호 */
public final class EndOfInputSignal extends FilterInputStream {

    private final CountDownLatch ended = new CountDownLatch(1);

    /** 감쌀 입력 스트림 */
    public EndOfInputSignal(InputStream input) {

        super(input);
    }

    /** 한 바이트 읽기, 끝이나 오류면 종료 신호 */
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

    /** 버퍼 읽기, 끝이나 오류면 종료 신호 */
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

    /** 종료 신호 후 스트림 닫기 */
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
