package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ProcessEngineTest
@SpringBootTest(classes = ProcessEngineTestCustomClockTest.TestApplication.class)
@Import(ProcessEngineTestCustomClockTest.CustomClockConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessEngineTestCustomClockTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ProcessEngineEmulator emulator;

    @Autowired
    @Qualifier("processEngineClock")
    private MutableClock processEngineClock;

    @Autowired
    @Qualifier("applicationClock")
    private MutableClock applicationClock;

    @Test
    @Order(1)
    void userClockShouldReplaceTestClock() {
        assertThat(processEngineClock.instant()).isEqualTo(START);

        emulator.fastForwardTime(Duration.ofHours(1));
        applicationClock.fastForwardTime(Duration.ofHours(2));

        assertThat(processEngineClock.instant()).isEqualTo(START.plus(Duration.ofHours(1)));
    }

    @Test
    @Order(2)
    void resetsOnlyClockUsedByEngine() {
        assertThat(processEngineClock.instant()).isEqualTo(START);
        assertThat(applicationClock.instant()).isEqualTo(START.plus(Duration.ofHours(2)));
    }

    @TestConfiguration
    static class CustomClockConfiguration {
        @Bean
        @Primary
        MutableClock applicationClock() {
            return new MutableClock(ZoneOffset.UTC, START, ChronoUnit.MICROS);
        }

        @Bean(defaultCandidate = false)
        MutableClock processEngineClock() {
            return new MutableClock(ZoneOffset.UTC, START, ChronoUnit.MICROS);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
