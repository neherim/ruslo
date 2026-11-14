package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.spring.config.property.ProcessEngineProperties;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ProcessEngineTest(resetTimeBeforeEachTest = false)
@SpringBootTest(classes = ProcessEngineTestClockResetDisabledTest.TestApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessEngineTestClockResetDisabledTest {

    private static final Instant TEST_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private ProcessEngineEmulator processEngineEmulator;

    @Autowired
    private ProcessEngineProperties properties;

    @Autowired
    @Qualifier("processEngineClock")
    private Clock processEngineClock;

    @Test
    @Order(1)
    void emulatorShouldSetEngineClockWithDispatcherDisabled() {
        assertThat(processEngine.isStarted()).isTrue();
        assertThat(properties.getDispatcher().isEnabled()).isFalse();

        processEngineEmulator.setTime(TEST_TIME);

        assertThat(processEngineClock.instant()).isEqualTo(TEST_TIME);
    }

    @Test
    @Order(2)
    void engineClockShouldNotBeResetWhenDisabled() {
        assertThat(processEngineClock.instant()).isEqualTo(TEST_TIME);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
