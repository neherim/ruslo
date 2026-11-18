package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.spring.config.ProcessEngineAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Test auto-configuration imported by {@link ProcessEngineTest}.
 * <p>
 * Registers a {@link MutableClock} named {@code rusloProcessEngineClock}, which replaces the system clock of
 * {@link ProcessEngineAutoConfiguration}, unless a bean with that name already exists. Also registers a
 * {@link ProcessEngineEmulator}. Tests change time with {@link ProcessEngineEmulator#fastForwardTime} and
 * {@link ProcessEngineEmulator#setTime}.
 * Importing this configuration directly does not reset the clock between tests; {@link ProcessEngineTest}
 * adds that behavior by default.
 */
@AutoConfiguration(before = ProcessEngineAutoConfiguration.class)
public class ProcessEngineTestConfiguration {

    /**
     * Returns the mutable time source with its initial time set at creation. Other {@link Clock} beans of the application
     * do not affect it.
     */
    @Bean(name = "rusloProcessEngineClock", defaultCandidate = false)
    @ConditionalOnMissingBean(name = "rusloProcessEngineClock")
    public MutableClock processEngineClock() {
        return new MutableClock();
    }

    /**
     * Returns an emulator that runs process steps synchronously on the engine clock.
     */
    @Bean("rusloProcessEngineEmulator")
    @ConditionalOnMissingBean
    public ProcessEngineEmulator processEngineEmulator(ProcessEngine processEngine) {
        return new ProcessEngineEmulator(processEngine);
    }
}
