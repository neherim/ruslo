package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.test.MutableTimeSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ProcessEngineTest
@SpringBootTest(classes = ProcessEngineTestCustomTimeSourceTest.TestApplication.class)
@Import(ProcessEngineTestCustomTimeSourceTest.CustomTimeSourceConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessEngineTestCustomTimeSourceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ProcessEngineEmulator emulator;

    @Autowired
    private MutableTimeSource timeSource;

    @Test
    @Order(1)
    void userTimeSourceShouldReplaceDefaultTestSource() {
        assertThat(timeSource.now()).isEqualTo(START);

        emulator.fastForwardTime(Duration.ofHours(1));

        assertThat(timeSource.now()).isEqualTo(START.plus(Duration.ofHours(1)));
    }

    @Test
    @Order(2)
    void resetsUserTimeSourceBeforeEachTest() {
        assertThat(timeSource.now()).isEqualTo(START);
    }

    @TestConfiguration
    static class CustomTimeSourceConfiguration {
        @Bean
        MutableTimeSource scenarioTime() {
            return new MutableTimeSource(START);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
