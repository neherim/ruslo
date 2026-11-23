package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.RusloTimeSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ProcessEngineTestConfigurationTest.TestApplication.class,
        properties = "ruslo.dispatcher.enabled=false"
)
@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessEngineTestConfigurationTest {

    private static final Instant TEST_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ProcessEngineEmulator processEngineEmulator;

    @Autowired
    private RusloTimeSource timeSource;

    @Test
    @Order(1)
    void emulatorShouldSetEngineTime() {
        processEngineEmulator.setTime(TEST_TIME);

        assertThat(timeSource.now()).isEqualTo(TEST_TIME);
    }

    @Test
    @Order(2)
    void engineTimeShouldNotBeResetWithoutProcessEngineTest() {
        assertThat(timeSource.now()).isEqualTo(TEST_TIME);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
