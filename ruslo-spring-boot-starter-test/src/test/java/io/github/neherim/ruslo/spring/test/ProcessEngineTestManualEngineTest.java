package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.spi.Database;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ProcessEngineTest
@SpringBootTest(classes = ProcessEngineTestManualEngineTest.TestApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessEngineTestManualEngineTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00.000000600Z");
    private static final Instant LATER = START.plusSeconds(60);

    @Autowired
    private ProcessEngineEmulator emulator;

    @Autowired
    @Qualifier("manualEngineClock")
    private MutableClock manualEngineClock;

    @Autowired
    @Qualifier("rusloProcessEngineClock")
    private MutableClock autoConfiguredClock;

    @Test
    @Order(1)
    void movesTheClockPassedToTheManualEngine() {
        autoConfiguredClock.setTime(Instant.EPOCH);
        emulator.setTime(LATER);

        assertThat(manualEngineClock.instant()).isEqualTo(LATER);
        assertThat(autoConfiguredClock.instant()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @Order(2)
    void resetsTheClockPassedToTheManualEngine() {
        assertThat(manualEngineClock.instant()).isEqualTo(START);
        assertThat(autoConfiguredClock.instant()).isEqualTo(Instant.EPOCH);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        MutableClock manualEngineClock() {
            return new MutableClock(ZoneOffset.UTC, START, ChronoUnit.NANOS);
        }

        @Bean
        ProcessEngine processEngine(Database database, @Qualifier("manualEngineClock") MutableClock clock) {
            return new ProcessEngineBuilder(database)
                .withClock(clock)
                .withDispatcherEnabled(false)
                .build();
        }
    }
}
