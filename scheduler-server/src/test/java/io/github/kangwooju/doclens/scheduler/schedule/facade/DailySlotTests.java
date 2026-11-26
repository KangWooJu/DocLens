package io.github.kangwooju.doclens.scheduler.schedule.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.kangwooju.doclens.testsupport.FakePeers;
import io.github.kangwooju.doclens.testsupport.PostgresTestConfiguration;

@SpringBootTest(properties = {
        "doclens.scheduler.initial-delay=1d",
        "doclens.scheduler.collect.enabled=true",
        "doclens.scheduler.collect.time=09:00"
})
@Import({
        PostgresTestConfiguration.class,
        DailySlotTests.FixedClock.class
})
@DisplayName("매일 수집 slot 등록")
class DailySlotTests {

    // 2026-11-27 08:59 KST
    private static final Instant BEFORE_TIME = Instant
            .parse("2026-11-26T23:59:00Z");

    private static final MovableClock CLOCK = new MovableClock(BEFORE_TIME);

    @Autowired
    private ScheduleFacade scheduleFacade;

    @Autowired
    private JdbcClient jdbcClient;

    // 요청 틀 파일
    @DynamicPropertySource
    static void requestFile(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.scheduler.request-file",
                        FakePeers::requestTemplateFile
                );
    }

    @Test
    @DisplayName("시각 전 없음, 시각 뒤 하나, 다시 불러도 하나, 활성 run이 끝나기 전 다음 날 slot은 대기")
    void registersOneSlotPerDay() {

        assertFalse(scheduleFacade
                .registerDue());
        assertEquals(
                List
                        .of(),
                slots()
        );

        CLOCK
                .set(Instant
                        .parse("2026-11-27T00:00:00Z"));
        assertTrue(scheduleFacade
                .registerDue());
        assertFalse(scheduleFacade
                .registerDue());
        assertEquals(
                List
                        .of("daily:2026-11-27"),
                slots()
        );

        CLOCK
                .set(Instant
                        .parse("2026-11-28T01:00:00Z"));
        assertFalse(scheduleFacade
                .registerDue());
        assertEquals(
                1,
                slots()
                        .size()
        );

        jdbcClient
                .sql("UPDATE scheduler.runs SET state = 'DONE', result = '{}'::jsonb")
                .update();
        assertTrue(scheduleFacade
                .registerDue());
        assertEquals(
                List
                        .of(
                                "daily:2026-11-27",
                                "daily:2026-11-28"
                        ),
                slots()
        );
    }

    // 등록된 slot 키
    private List<String> slots() {

        return jdbcClient
                .sql("SELECT slot_key FROM scheduler.runs ORDER BY slot_key")
                .query(String.class)
                .list();
    }

    /** 시험이 움직이는 시계 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        // 기본 시계 대신 쓰는 시험 시계
        @Bean
        @Primary
        Clock movableClock() {

            return CLOCK;
        }
    }

    /** 값을 바꿀 수 있는 UTC 시계 */
    static final class MovableClock extends Clock {

        private volatile Instant now;

        // 시작 시각
        MovableClock(Instant now) {

            this.now = now;
        }

        // 현재 시각 바꾸기
        void set(Instant now) {

            this.now = now;
        }

        /** UTC */
        @Override
        public ZoneId getZone() {

            return ZoneOffset.UTC;
        }

        /** 같은 시각을 다른 시간대로 보는 시계 */
        @Override
        public Clock withZone(ZoneId zone) {

            MovableClock owner = this;

            return new Clock() {

                /** 지정한 시간대 */
                @Override
                public ZoneId getZone() {

                    return zone;
                }

                /** 시간대 변경 */
                @Override
                public Clock withZone(ZoneId other) {

                    return owner
                            .withZone(other);
                }

                /** 원래 시계의 현재 시각 */
                @Override
                public Instant instant() {

                    return owner
                            .instant();
                }
            };
        }

        /** 현재 시각 */
        @Override
        public Instant instant() {

            return now;
        }
    }
}
