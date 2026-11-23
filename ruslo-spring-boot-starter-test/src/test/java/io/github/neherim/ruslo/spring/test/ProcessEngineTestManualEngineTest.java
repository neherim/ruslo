package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.test.MutableTimeSource;
import io.github.neherim.ruslo.core.spi.Database;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.time.Instant;

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
    private TestApplication application;

    @Autowired
    private MutableTimeSource autoConfiguredTimeSource;

    @Test
    @Order(1)
    void movesTheTimeSourcePassedToTheManualEngine() {
        autoConfiguredTimeSource.setTime(Instant.EPOCH);
        emulator.setTime(LATER);

        assertThat(application.manualTimeSource.now()).isEqualTo(LATER);
        assertThat(autoConfiguredTimeSource.now()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @Order(2)
    void resetsTheTimeSourcePassedToTheManualEngine() {
        assertThat(application.manualTimeSource.now()).isEqualTo(START);
        assertThat(autoConfiguredTimeSource.now()).isEqualTo(Instant.EPOCH);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
        private final MutableTimeSource manualTimeSource = new MutableTimeSource(START);

        @Bean
        ProcessEngine processEngine(Database database) {
            return new ProcessEngineBuilder(database)
                .withTimeSource(manualTimeSource)
                .withDispatcherEnabled(false)
                .build();
        }
    }
}
