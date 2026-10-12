package io.github.neherim.ruslo.spring.definition;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.spring.config.ProcessEngineAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringProcessDefinitionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                ProcessEngineAutoConfiguration.class))
        .withUserConfiguration(TestConfiguration.class)
        .withPropertyValues("ruslo.dispatcher.enabled=false");

    @Test
    void beanShouldBeResolvedFromContextWithoutCycleOnProcessEngine() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var engine = context.getBean(ProcessEngine.class);
            var emulator = new ProcessEngineEmulator(engine);

            var id = engine.newProcess(ProcessType.of("SPRING_BEAN")).start();
            emulator.runUntilStop(id);

            assertThat(context.getBean(CountingStep.class).executions.get()).isEqualTo(1);
            assertThat(engine.processes().byId(id).single().orElseThrow().state().name()).isEqualTo("FINISH");
        });
    }

    @Test
    void beanOutsideSpringContextShouldFail() {
        var definition = new TestDefinition();
        assertThatThrownBy(() -> definition.define(new ProcessSpecBuilder()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not a Spring bean");
    }

    enum State implements ProcessState {
        INIT,
        FINISH
    }

    static class CountingStep implements ProcessStep {
        // injecting ProcessEngine checks that there is no circular dependency
        @Autowired
        ProcessEngine processEngine;
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public void execute(ProcessContext context) {
            executions.incrementAndGet();
        }
    }

    static class TestDefinition extends SpringProcessDefinition {
        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder
                .type("SPRING_BEAN")
                .initialState(State.INIT)
                .step(State.INIT, bean(CountingStep.class), State.FINISH)
                .terminal(State.FINISH)
                .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        CountingStep countingStep() {
            return new CountingStep();
        }

        @Bean
        TestDefinition testDefinition() {
            return new TestDefinition();
        }
    }
}
