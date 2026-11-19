package io.github.kangwooju.doclens.mcp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.kangwooju.doclens.mcp.global.cache.CacheDirectory;
import io.github.kangwooju.doclens.mcp.global.mcp.EndOfInputSignal;
import io.github.kangwooju.doclens.mcp.global.mcp.dto.ProtocolStreams;

/**
 * 로컬 STDIO MCP 서버 진입점
 * stdout은 JSON-RPC 전용, stdin이 닫히면 프로세스 종료
 */
@SpringBootApplication
public class McpServerApplication {

    /** 로그 디렉터리 시스템 속성 이름(logback-spring.xml에서 사용) */
    public static final String LOG_DIR_PROPERTY = "doclens.log.dir";

    /** stdout을 프로토콜 전용으로 분리한 뒤 서버 실행 */
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
                .addInitializers(context -> {
                    ConfigurableListableBeanFactory beans = context
                            .getBeanFactory();
                    beans
                            .registerSingleton(
                                    "protocolStreams",
                                    new ProtocolStreams(
                                            input,
                                            protocolOutput,
                                            input::signalEnd
                                    )
                            );
                    beans
                            .registerSingleton(
                                    "cacheDirectory",
                                    cacheDirectory
                            );
                });

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
