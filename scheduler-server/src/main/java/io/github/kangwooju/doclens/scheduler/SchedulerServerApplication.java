package io.github.kangwooju.doclens.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SchedulerServerApplication {

    /** scheduler-server 실행, command profile이면 명령 종료 코드로 끝냄 */
    public static void main(String[] args) {

        ConfigurableApplicationContext context = SpringApplication
                .run(
                        SchedulerServerApplication.class,
                        args
                );

        if (context
                .getEnvironment()
                .matchesProfiles("command")) {
            System
                    .exit(SpringApplication
                            .exit(context));
        }
    }
}
