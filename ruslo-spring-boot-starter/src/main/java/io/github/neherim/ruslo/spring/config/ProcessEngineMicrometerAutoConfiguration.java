package io.github.neherim.ruslo.spring.config;

import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.spring.metrics.MicrometerProcessEngineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Configuration for Ruslo metrics.
 * <p>
 * This configuration automatically registers metrics collection when Micrometer
 * is present in the classpath.
 */
@AutoConfiguration(
        after = {MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class},
        before = ProcessEngineAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class ProcessEngineMicrometerAutoConfiguration {

    /**
     * Creates a Micrometer-based metrics implementation when MeterRegistry is available.
     *
     * @param registry the Micrometer registry
     * @return the Micrometer metrics implementation
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public ProcessEngineMetrics micrometerProcessEngineMetrics(MeterRegistry registry) {
        return new MicrometerProcessEngineMetrics(registry);
    }
}
