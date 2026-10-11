package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.spring.config.ProcessEngineAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Test auto-configuration imported by {@link ProcessEngineTest}.
 * <p>
 * Registers a {@link MutableClock} named {@code processEngineClock}, which replaces the system clock of
 * {@link ProcessEngineAutoConfiguration}, and a {@link ProcessEngineEmulator}. Tests move time with
 * {@link ProcessEngineEmulator#fastForwardTime} and {@link ProcessEngineEmulator#setTime};
 * {@link ProcessEngineTestExecutionListener} resets the clock before each test.
 */
@AutoConfiguration(before = ProcessEngineAutoConfiguration.class)
public class ProcessEngineTestConfiguration {

    /**
     * Returns the engine clock, fixed at the creation time. Other {@link Clock} beans of the application
     * do not affect it.
     */
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "processEngineClock")
    public MutableClock processEngineClock() {
        return new MutableClock();
    }

    /**
     * Returns an emulator that runs process steps synchronously on the engine clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessEngineEmulator processEngineEmulator(ProcessEngine processEngine) {
        return new ProcessEngineEmulator(processEngine);
    }
}
