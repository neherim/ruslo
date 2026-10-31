package io.github.neherim.ruslo.spring.config;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.spring.config.property.ProcessEngineProperties;
import io.github.neherim.ruslo.spring.definitions.SimpleProcessDefinition;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessEngineAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProcessEngineAutoConfiguration.class));

    @Test
    void testApplicationContextStart() {
        this.contextRunner.with(database()).withUserConfiguration(EmptyConfiguration.class).run((context) -> {
            assertThat(context).hasSingleBean(ProcessEngine.class);
            assertThat(context).hasSingleBean(ProcessEngineAdmin.class);
            assertThat(context).hasSingleBean(ProcessEngineInitializer.class);
            assertThat(context.getBean(ProcessEngineInitializer.class)).isInstanceOf(SmartLifecycle.class);
        });
    }

    @Test
    void adminUiPropertiesShouldNotPreventEngineStartup() {
        for (String enabled : new String[]{"true", "false"}) {
            this.contextRunner.with(database())
                .withUserConfiguration(EmptyConfiguration.class)
                .withPropertyValues(
                        "ruslo.admin-ui.enabled=" + enabled,
                        "ruslo.admin-ui.base-path=/processes")
                .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void defaultWorkersShouldBeRegistered() {
        this.contextRunner.with(database()).withUserConfiguration(EmptyConfiguration.class).run((context) -> {
            var processEngine = context.getBean(ProcessEngine.class);
            var workerSpecs = new ProcessEngineAdmin(processEngine).listWorkerSpecifications();
            assertThat(workerSpecs).hasSize(1);
        });
    }

    @Test
    void lowercaseDefaultWorkerPropertyShouldConfigureDefaultWorker() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class)
            .withPropertyValues(
                    "ruslo.workers.default.execution-mode=transactional",
                    "ruslo.workers.default.max-concurrency=3")
            .run((context) -> {
                var processEngine = context.getBean(ProcessEngine.class);
                var workerSpecs = new ProcessEngineAdmin(processEngine).listWorkerSpecifications();

                assertThat(workerSpecs).singleElement().satisfies((workerSpec) -> {
                    assertThat(workerSpec.workerId()).isEqualTo(WorkerId.DEFAULT);
                    assertThat(workerSpec.maxConcurrency()).isEqualTo(3);
                });
            });
    }

    @Test
    void executionModePropertyShouldAcceptAnyCaseAndSeparator() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class)
            .withPropertyValues(
                    "ruslo.workers.snake.execution-mode=non_transactional",
                    "ruslo.workers.kebab.execution-mode=non-transactional",
                    "ruslo.workers.upper.execution-mode=NON_TRANSACTIONAL")
            .run(context -> assertThat(context.getBean(ProcessEngineAdmin.class).listWorkerSpecifications())
                .filteredOn(spec -> !spec.workerId().equals(WorkerId.DEFAULT))
                .hasSize(3)
                .allSatisfy(spec -> assertThat(spec.executionMode())
                    .isEqualTo(WorkerSpec.ExecutionMode.NON_TRANSACTIONAL)));
    }

    @Test
    void unknownExecutionModeShouldFailBinding() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class)
            .withPropertyValues("ruslo.workers.io.execution-mode=async")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void processEngineShouldRunWhileDefaultPhaseLifecycleBeansStartAndStop() {
        var lifecycle = new EngineUsingLifecycle();

        this.contextRunner.with(database())
            .withUserConfiguration(ProcessDefinitionConfiguration.class)
            .withBean(EngineUsingLifecycle.class, () -> lifecycle)
            .run(context -> assertThat(context).hasNotFailed());

        assertThat(lifecycle.startedProcesses).hasValue(2);
    }

    @Test
    void removedWorkerPoolPropertyShouldFailBinding() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class)
            .withPropertyValues("ruslo.workers.default.thread-pool=fixed")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void overrideDefaultWorker() {
        var overrideDefaultWorker = new OverrideDefaultWorker().defaultWorker();

        this.contextRunner.with(database()).withUserConfiguration(OverrideDefaultWorker.class).run((context) -> {
            var processEngine = context.getBean(ProcessEngine.class);
            var workerSpecs = new ProcessEngineAdmin(processEngine).listWorkerSpecifications();
            assertThat(workerSpecs).hasSize(1);
            assertThat(workerSpecs).contains(overrideDefaultWorker);
        });
    }

    @Test
    void defineCustomWorker() {
        var customWorker = new CustomWorker().reportGeneratorWorker();

        this.contextRunner.with(database()).withUserConfiguration(CustomWorker.class).run((context) -> {
            var processEngine = context.getBean(ProcessEngine.class);
            var workerSpecs = new ProcessEngineAdmin(processEngine).listWorkerSpecifications();
            assertThat(workerSpecs).hasSize(2);
            assertThat(workerSpecs).contains(customWorker);
        });
    }

    @Test
    void instanceLifecyclePropertiesShouldBeBound() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class)
            .withPropertyValues(
                    "ruslo.dispatcher.instance-heartbeat-interval=20s",
                    "ruslo.dispatcher.instance-timeout=90s")
            .run((context) -> {
                var dispatcher = context.getBean(ProcessEngineProperties.class).getDispatcher();

                assertThat(dispatcher.getInstanceHeartbeatInterval()).isEqualTo(Duration.ofSeconds(20));
                assertThat(dispatcher.getInstanceTimeout()).isEqualTo(Duration.ofSeconds(90));
            });
    }

    @Test
    void historyEnabledPropertyShouldBeAppliedToProcessEngine() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class, ProcessDefinitionConfiguration.class)
            .withPropertyValues("ruslo.history.enabled=false")
            .run((context) -> {
                var processEngine = context.getBean(ProcessEngine.class);

                processEngine.newProcess(SimpleProcessDefinition.TYPE).start();

                var jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
                var historyRows = jdbcTemplate.queryForObject(
                        "select count(*) from xs_process_state_hist", Integer.class);

                assertThat(historyRows).isZero();
            });
    }

    @Test
    void customLoggingMdcPropertiesShouldBeAppliedToProcessEngine() {
        this.contextRunner.with(database())
            .withUserConfiguration(EmptyConfiguration.class, ProcessDefinitionConfiguration.class)
            .withPropertyValues(
                    "ruslo.logging.mdc.correlation-id-key=spring_correlation",
                    "ruslo.logging.mdc.process-id-key=spring_process",
                    "ruslo.logging.mdc.process-type-key=spring_type")
            .run((context) -> {
                var processEngine = context.getBean(ProcessEngine.class);
                MDC.put("spring_correlation", "spring-correlation-id");
                try {
                    var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
                    var process = processEngine.processes().byId(processId).singleDetails().orElseThrow();

                    assertThat(process.correlationId()).isEqualTo("spring-correlation-id");
                } finally {
                    MDC.clear();
                }
            });
    }

    @Test
    void processDefinitionsUseConstructorInjectedInstancesAndTypedHandlers() {
        this.contextRunner.with(database())
            .withUserConfiguration(ConstructorInjectionConfiguration.class)
            .withPropertyValues("ruslo.dispatcher.enabled=false")
            .run(context -> {
                var processEngine = context.getBean(ProcessEngine.class);
                var emulator = new ProcessEngineEmulator(processEngine);
                var actionExecutions = context.getBean(ConstructorInjectedAction.class).executions;
                var handlers = context.getBean(ConstructorInjectedHandlers.class);

                var paymentProcess = processEngine.newProcess(ConstructorInjectedDefinition.TYPE).start();
                emulator.runUntilStop(paymentProcess);
                emulator.sendMessageAndRunUntilStop(paymentProcess,
                        ConstructorInjectedDefinition.PAYMENT, new PaymentPayload("tx-1"));

                var cancellationProcess = processEngine.newProcess(ConstructorInjectedDefinition.TYPE).start();
                emulator.runUntilStop(cancellationProcess);
                emulator.sendMessageAndRunUntilStop(cancellationProcess,
                        ConstructorInjectedDefinition.CANCELLATION, "customer-request");

                assertThat(actionExecutions.get()).isEqualTo(2);
                assertThat(handlers.paymentExecutions.get()).isEqualTo(1);
                assertThat(handlers.cancellationExecutions.get()).isEqualTo(1);
                assertThat(processEngine.processes().byId(paymentProcess).single().orElseThrow().state().name())
                    .isEqualTo(ConstructorInjectedState.COMPLETED.name());
                assertThat(processEngine.processes().byId(cancellationProcess).single().orElseThrow().state().name())
                    .isEqualTo(ConstructorInjectedState.CANCELLED.name());
            });
    }

    @Test
    void processStepsMayDependOnProcessEngineWithoutCircularReferences() {
        this.contextRunner.with(database())
            .withUserConfiguration(EngineAwareStepConfiguration.class)
            .withPropertyValues("ruslo.dispatcher.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var processEngine = context.getBean(ProcessEngine.class);
                var emulator = new ProcessEngineEmulator(processEngine);

                var parentId = processEngine.newProcess(EngineAwareDefinition.PARENT).start();
                emulator.runUntilStop(parentId);

                var children = processEngine.processes().byType(EngineAwareDefinition.CHILD).list();
                assertThat(children).hasSize(1);
                assertThat(context.getBean(ChildStarter.class).processEngine).isSameAs(processEngine);
            });
    }

    @Test
    void applicationClockShouldNotReplaceEngineClock() {
        this.contextRunner.with(database())
            .withUserConfiguration(ProcessDefinitionConfiguration.class, ApplicationClockConfiguration.class)
            .withPropertyValues("ruslo.dispatcher.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Clock.class)).isSameAs(ApplicationClockConfiguration.CLOCK);
                assertThat(context.getBean("processEngineClock", Clock.class))
                    .isNotSameAs(ApplicationClockConfiguration.CLOCK);

                var processEngine = context.getBean(ProcessEngine.class);
                var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
                var process = processEngine.processes().byId(processId).single().orElseThrow();

                assertThat(process.createdAt()).isNotEqualTo(ApplicationClockConfiguration.CLOCK.instant());
            });
    }

    @Test
    void customProcessEngineBeanShouldNotReceiveDefinitionsAutomatically() {
        this.contextRunner.with(database())
            .withUserConfiguration(CustomEngineConfiguration.class, ProcessDefinitionConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var processEngine = context.getBean(ProcessEngine.class);
                assertThat(context).hasSingleBean(ProcessEngineInitializer.class);
                assertThat(processEngine.isStarted()).isTrue();
                assertThat(new ProcessEngineAdmin(processEngine).listProcessSpecifications()).isEmpty();
            });
    }

    /**
     * Simulates a web server or message listener: runs in the default phase and starts a process
     * on start and on stop.
     */
    static final class EngineUsingLifecycle implements SmartLifecycle {
        private final AtomicInteger startedProcesses = new AtomicInteger();
        private ProcessEngine processEngine;
        private volatile boolean running;

        @Autowired
        void setProcessEngine(ProcessEngine processEngine) {
            this.processEngine = processEngine;
        }

        @Override
        public void start() {
            processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
            startedProcesses.incrementAndGet();
            running = true;
        }

        @Override
        public void stop() {
            processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
            startedProcesses.incrementAndGet();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    static class CustomEngineConfiguration {
        @Bean
        ProcessEngine customProcessEngine(
                DataSource dataSource,
                org.springframework.transaction.PlatformTransactionManager tm
        ) {
            return new io.github.neherim.ruslo.core.api.ProcessEngineBuilder(
                    new io.github.neherim.ruslo.spring.integration.SpringJdbcDatabase(tm, dataSource))
                .withDispatcherEnabled(false)
                .build();
        }
    }

    /** Application service that needs ProcessEngine; a step depends on it. */
    static final class ChildStarter {
        private final ProcessEngine processEngine;

        ChildStarter(ProcessEngine processEngine) {
            this.processEngine = processEngine;
        }

        void startChild() {
            processEngine.newProcess(EngineAwareDefinition.CHILD).start();
        }
    }

    static final class StartChildStep implements ProcessStep {
        private final ChildStarter childStarter;

        StartChildStep(ChildStarter childStarter) {
            this.childStarter = childStarter;
        }

        @Override
        public void execute(ProcessContext context) {
            childStarter.startChild();
        }
    }

    static final class EngineAwareDefinition implements ProcessDefinition {
        static final ProcessType PARENT = ProcessType.of("ENGINE_AWARE_PARENT");
        static final ProcessType CHILD = ProcessType.of("ENGINE_AWARE_CHILD");
        private final StartChildStep step;

        EngineAwareDefinition(StartChildStep step) {
            this.step = step;
        }

        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(PARENT)
                .initialState(EngineAwareState.INIT)
                .step(EngineAwareState.INIT, step, EngineAwareState.DONE)
                .terminal(EngineAwareState.DONE)
                .build();
        }
    }

    static final class ChildDefinition implements ProcessDefinition {
        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(EngineAwareDefinition.CHILD)
                .initialState(EngineAwareState.INIT)
                .step(EngineAwareState.INIT, context -> {}, EngineAwareState.DONE)
                .terminal(EngineAwareState.DONE)
                .build();
        }
    }

    static class EngineAwareStepConfiguration {
        @Bean
        ChildStarter childStarter(ProcessEngine processEngine) {
            return new ChildStarter(processEngine);
        }

        @Bean
        StartChildStep startChildStep(ChildStarter childStarter) {
            return new StartChildStep(childStarter);
        }

        @Bean
        ProcessDefinition engineAwareDefinition(StartChildStep step) {
            return new EngineAwareDefinition(step);
        }

        @Bean
        ProcessDefinition childDefinition() {
            return new ChildDefinition();
        }
    }

    enum EngineAwareState implements ProcessState {
        INIT,
        DONE
    }

    private Function<ApplicationContextRunner, ApplicationContextRunner> database() {
        return (runner) -> runner
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class));
    }

    static class EmptyConfiguration {

    }

    static class ApplicationClockConfiguration {
        static final Clock CLOCK = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Bean
        public Clock applicationClock() {
            return CLOCK;
        }
    }

    static class ProcessDefinitionConfiguration {

        @Bean
        public ProcessDefinition simpleProcessDefinition() {
            return new SimpleProcessDefinition();
        }
    }

    static class ConstructorInjectionConfiguration {

        @Bean
        ConstructorInjectedAction constructorInjectedAction() {
            return new ConstructorInjectedAction();
        }

        @Bean
        ConstructorInjectedHandlers constructorInjectedHandlers() {
            return new ConstructorInjectedHandlers();
        }

        @Bean
        ProcessDefinition constructorInjectedDefinition(
                ConstructorInjectedAction action,
                ConstructorInjectedHandlers handlers
        ) {
            return new ConstructorInjectedDefinition(action, handlers);
        }
    }

    static final class ConstructorInjectedDefinition implements ProcessDefinition {
        static final ProcessType TYPE = ProcessType.of("CONSTRUCTOR_INJECTED");
        static final MessageType<PaymentPayload> PAYMENT = MessageType.of("payment", PaymentPayload.class);
        static final MessageType<String> CANCELLATION = MessageType.of("cancellation", String.class);

        private final ConstructorInjectedAction action;
        private final ConstructorInjectedHandlers handlers;

        ConstructorInjectedDefinition(ConstructorInjectedAction action, ConstructorInjectedHandlers handlers) {
            this.action = action;
            this.handlers = handlers;
        }

        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(TYPE)
                .initialState(ConstructorInjectedState.CREATED)
                .step(ConstructorInjectedState.CREATED, action, ConstructorInjectedState.WAITING)
                .await(ConstructorInjectedState.WAITING, await -> await
                    .message(PAYMENT, handlers::onPayment, ConstructorInjectedState.COMPLETED)
                    .message(CANCELLATION, handlers::onCancellation, ConstructorInjectedState.CANCELLED))
                .terminal(ConstructorInjectedState.COMPLETED)
                .terminal(ConstructorInjectedState.CANCELLED)
                .build();
        }
    }

    static final class ConstructorInjectedAction implements ProcessStep {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public void execute(ProcessContext context) {
            executions.incrementAndGet();
        }
    }

    static final class ConstructorInjectedHandlers {
        private final AtomicInteger paymentExecutions = new AtomicInteger();
        private final AtomicInteger cancellationExecutions = new AtomicInteger();

        void onPayment(ProcessContext context, IncomingMessage<PaymentPayload> message) {
            paymentExecutions.incrementAndGet();
        }

        void onCancellation(ProcessContext context, IncomingMessage<String> message) {
            cancellationExecutions.incrementAndGet();
        }
    }

    record PaymentPayload(String transactionId) {
    }

    enum ConstructorInjectedState implements ProcessState {
        CREATED,
        WAITING,
        COMPLETED,
        CANCELLED
    }

    static class OverrideDefaultWorker {

        @Bean
        public WorkerSpec defaultWorker() {
            return WorkerSpec.builder(WorkerId.DEFAULT)
                .transactional()
                .maxConcurrency(5)
                .build();
        }
    }

    static class CustomWorker {

        @Bean
        public WorkerSpec reportGeneratorWorker() {
            return WorkerSpec.builder(new WorkerId("REPORT_GENERATOR"))
                .nonTransactional()
                .maxConcurrency(30)
                .build();
        }
    }

}
