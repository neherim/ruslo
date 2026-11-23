package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.RusloTimeSource;
import io.github.neherim.ruslo.core.api.test.MutableTimeSource;
import io.github.neherim.ruslo.spring.config.ProcessEngineAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Test auto-configuration imported by {@link ProcessEngineTest}.
 * <p>
 * Registers a {@link MutableTimeSource} in place of the system time source of
 * {@link ProcessEngineAutoConfiguration}, unless a {@link RusloTimeSource} bean already exists. Also registers a
 * {@link ProcessEngineEmulator}. Tests change time with {@link ProcessEngineEmulator#fastForwardTime} and
 * {@link ProcessEngineEmulator#setTime}.
 * Importing this configuration directly does not reset time between tests; {@link ProcessEngineTest}
 * adds that behavior by default.
 */
@AutoConfiguration(before = ProcessEngineAutoConfiguration.class)
public class ProcessEngineTestConfiguration {

    /** Returns a mutable time source initially set to the current system time. */
    @Bean
    @ConditionalOnMissingBean(RusloTimeSource.class)
    public MutableTimeSource rusloTestTimeSource() {
        return new MutableTimeSource();
    }

    /**
     * Returns an emulator that runs process steps synchronously using the engine's time source.
     */
    @Bean("rusloProcessEngineEmulator")
    @ConditionalOnMissingBean
    public ProcessEngineEmulator processEngineEmulator(ProcessEngine processEngine) {
        return new ProcessEngineEmulator(processEngine);
    }
}
