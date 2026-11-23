package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.RusloTimeSource;
import io.github.neherim.ruslo.spring.config.property.ProcessEngineProperties;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

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
    private RusloTimeSource timeSource;

    @Test
    void shouldRegisterProcessEngineEmulatorAndDisableDispatcherAutoStart() {
        assertThat(processEngine.isStarted()).isTrue();
        assertThat(processEngineEmulator).isNotNull();
        assertThat(properties.getDispatcher().isEnabled()).isFalse();
    }

    @Test
    @Order(1)
    void emulatorShouldMoveEngineTime() {
        timeBeforeFastForward = timeSource.now();

        processEngineEmulator.fastForwardTime(Duration.ofDays(1));

        assertThat(timeSource.now()).isEqualTo(timeBeforeFastForward.plus(Duration.ofDays(1)));
    }

    @Test
    @Order(2)
    void engineTimeShouldBeResetBeforeEachTest() {
        assertThat(timeSource.now()).isEqualTo(timeBeforeFastForward);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
