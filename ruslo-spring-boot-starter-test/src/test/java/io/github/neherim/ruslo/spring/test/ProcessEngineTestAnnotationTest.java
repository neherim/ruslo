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
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ProcessEngineTest
@SpringBootTest(classes = ProcessEngineTestAnnotationTest.TestApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessEngineTestAnnotationTest {

    private static Instant timeBeforeFastForward;

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
    void shouldRegisterProcessEngineEmulatorAndDisableDispatcherAutoStart() {
        assertThat(processEngine.isStarted()).isTrue();
        assertThat(processEngineEmulator).isNotNull();
        assertThat(properties.getDispatcher().isEnabled()).isFalse();
    }

    @Test
    @Order(1)
    void emulatorShouldMoveEngineClock() {
        timeBeforeFastForward = processEngineClock.instant();

        processEngineEmulator.fastForwardTime(Duration.ofDays(1));

        assertThat(processEngineClock.instant()).isEqualTo(timeBeforeFastForward.plus(Duration.ofDays(1)));
    }

    @Test
    @Order(2)
    void engineClockShouldBeResetBeforeEachTest() {
        assertThat(processEngineClock.instant()).isEqualTo(timeBeforeFastForward);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
