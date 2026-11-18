package io.github.kangwooju.doclens.mcp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.kangwooju.doclens.mcp.global.cache.CacheDirectory;
import io.github.kangwooju.doclens.mcp.global.mcp.EndOfInputSignal;
import io.github.kangwooju.doclens.mcp.global.mcp.ProtocolStreams;

/**
 * 로컬 STDIO MCP 서버 진입점
 *
 * <p>stdout은 JSON-RPC 전용, 그 밖의 System.out 출력은 stderr로 전환
 * <p>stdin 종료 또는 SDK 세션 종료 시 Spring 컨텍스트 종료와 프로세스 종료
 */
@SpringBootApplication
public class McpServerApplication {

    /** 로그 디렉터리 시스템 속성 이름(logback-spring.xml에서 사용) */
    public static final String LOG_DIR_PROPERTY = "doclens.log.dir";

    public static void main(String[] args) {

        OutputStream protocolOutput = new FileOutputStream(FileDescriptor.out);
        System
                .setOut(System.err);

        CacheDirectory cacheDirectory = cacheDirectoryOrExit();

        if (System
                .getProperty(LOG_DIR_PROPERTY) == null) {
            System
                    .setProperty(
                            LOG_DIR_PROPERTY,
                            cacheDirectory
                                    .logs()
                                    .toString()
                    );
        }

        EndOfInputSignal input = new EndOfInputSignal(System.in);
        SpringApplication application = new SpringApplication(McpServerApplication.class);
        application
                .addInitializers(context -> context
                        .getBeanFactory()
                        .registerSingleton(
                                "protocolStreams",
                                new ProtocolStreams(
                                        input,
                                        protocolOutput,
                                        input::signalEnd
                                )
                        ));

        ConfigurableApplicationContext context = application
                .run(args);
        input
                .awaitEnd();
        System
                .exit(SpringApplication
                        .exit(context));
    }

    // 캐시 루트 결정, 실패 시 stderr 안내와 종료 코드 1
    private static CacheDirectory cacheDirectoryOrExit() {

        try {
            return CacheDirectory
                    .resolve(
                            System
                                    .getenv(),
                            System
                                    .getProperty("os.name"),
                            System
                                    .getProperty("user.home")
                    );
        } catch (IllegalStateException exception) {
            System.err
                    .println("doclens-mcp: " + exception
                            .getMessage());
            System
                    .exit(1);

            throw exception;
        }
    }
}
