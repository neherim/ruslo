package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessEngineBuilderTest extends BaseTest {

    @Test
    void dispatchIntervalsShouldRejectNullImmediately() {
        var builder = new ProcessEngineBuilder(database);

        assertEquals("Minimum process dispatch interval must not be null",
                assertThrows(NullPointerException.class, () -> builder.withMinProcessDispatchInterval(null))
                    .getMessage());
        assertEquals("Maximum process dispatch interval must not be null",
                assertThrows(NullPointerException.class, () -> builder.withMaxProcessDispatchInterval(null))
                    .getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidDispatchIntervals")
    void dispatchIntervalsShouldRejectUnsafeValuesImmediately(Duration interval) {
        var builder = new ProcessEngineBuilder(database);

        assertEquals("Minimum process dispatch interval must be between 1 and " + Long.MAX_VALUE / 2 + " milliseconds",
                assertThrows(IllegalArgumentException.class, () -> builder.withMinProcessDispatchInterval(interval))
                    .getMessage());
        assertEquals("Maximum process dispatch interval must be between 1 and " + Long.MAX_VALUE / 2 + " milliseconds",
                assertThrows(IllegalArgumentException.class, () -> builder.withMaxProcessDispatchInterval(interval))
                    .getMessage());
    }

    private static Stream<Duration> invalidDispatchIntervals() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(-1),
                Duration.ofNanos(1), Duration.ofNanos(999_999),
                Duration.ofMillis(Long.MAX_VALUE / 2 + 1), Duration.ofMillis(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MIN_VALUE));
    }

    @ParameterizedTest
    @MethodSource("validDispatchIntervals")
    void dispatchIntervalsShouldAcceptSupportedRange(Duration interval) {
        try (
                var engine = new ProcessEngineBuilder(database)
                    .withMinProcessDispatchInterval(interval)
                    .withMaxProcessDispatchInterval(interval)
                    .build()
        ) {
            assertNotNull(engine);
        }
    }

    private static Stream<Duration> validDispatchIntervals() {
        return Stream.of(Duration.ofMillis(1), Duration.ofNanos(1_999_999),
                Duration.ofMillis(Long.MAX_VALUE / 2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void dispatchIntervalsShouldAllowConfigurationInEitherOrder(boolean minFirst) {
        var builder = new ProcessEngineBuilder(database);
        if (minFirst) {
            builder.withMinProcessDispatchInterval(Duration.ofSeconds(30))
                .withMaxProcessDispatchInterval(Duration.ofSeconds(60));
        } else {
            builder.withMaxProcessDispatchInterval(Duration.ofMillis(10))
                .withMinProcessDispatchInterval(Duration.ofMillis(1));
        }

        try (var engine = builder.build()) {
            assertNotNull(engine);
        }
    }

    @Test
    void buildShouldRejectInvertedDispatchIntervalsBeforeAccessingDatabase() {
        var countingDatabase = new CountingDatabase(database.getDataSource());
        var builder = new ProcessEngineBuilder(countingDatabase)
            .withDispatcherEnabled(false)
            .withMinProcessDispatchInterval(Duration.ofSeconds(11));

        var exception = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("Minimum process dispatch interval must not exceed maximum process dispatch interval",
                exception.getMessage());
        assertEquals(0, countingDatabase.dataSourceAccesses);
    }

    @Test
    void builderRuntimeShouldExecuteProcessThroughLeaseDispatcher() {
        var type = ProcessType.of("LEASE_RUNTIME");
        var metrics = new InMemoryProcessEngineMetrics();
        var processEngine = new ProcessEngineBuilder(database)
            .addProcessDefinition(definition(type))
            .withMetrics(metrics)
            .withMinProcessDispatchInterval(Duration.ofSeconds(30))
            .withMaxProcessDispatchInterval(Duration.ofSeconds(30))
            .build();
        try {
            processEngine.start();
            await().atMost(Duration.ofSeconds(2))
                .until(() -> database.queryForObject("select count(*) from xs_application_instance",
                        rs -> rs.getInt(1))
                    .orElseThrow() > 0);
            var processId = processEngine.newProcess(type).start();

            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(true,
                        processEngine.processes()
                            .byId(processId)
                            .singleDetails()
                            .orElseThrow()
                            .status() == ProcessStatus.COMPLETED));
        } finally {
            processEngine.close();
        }
    }

    @Test
    void shouldRegisterSameDefinitionClassUnderDifferentTypesDuringBuild() {
        var firstType = ProcessType.of("FIRST");
        var secondType = ProcessType.of("SECOND");
        var processEngine = new ProcessEngineBuilder(database)
            .addProcessDefinition(new ConfigurableDefinition(firstType))
            .addProcessDefinitions(List.of(new ConfigurableDefinition(secondType)))
            .build();

        var registeredTypes = new ProcessEngineAdmin(processEngine).listProcessSpecifications()
            .stream()
            .map(spec -> spec.type().name())
            .toList();

        assertEquals(2, registeredTypes.size());
        assertEquals(List.of("FIRST", "SECOND"), registeredTypes.stream().sorted().toList());
    }

    @Test
    void shouldRejectDuplicateProcessTypeDuringBuild() {
        var type = ProcessType.of("DUPLICATE");
        var builder = new ProcessEngineBuilder(database)
            .addProcessDefinition(definition(type))
            .addProcessDefinition(definition(type));

        var exception = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("Process definition with type DUPLICATE already registered",
                exception.getMessage());
    }

    @Test
    void shouldRegisterDefinitionAfterBuildAndRunIt() {
        var type = ProcessType.of("LATE");
        var processEngine = newEngineWithoutDefinitions();

        processEngine.registerDefinition(definition(type)).start();
        var processId = processEngine.newProcess(type).start();

        var process = emulator(processEngine).runUntilStop(processId);
        assertEquals(ProcessStatus.COMPLETED, process.status());
        assertEquals(List.of("LATE"), new ProcessEngineAdmin(processEngine).listProcessSpecifications()
            .stream()
            .map(spec -> spec.type().name())
            .toList());
    }

    @Test
    void shouldAllowRegistrationAfterStart() {
        var type = ProcessType.of("AFTER_START");
        var processEngine = newEngineWithoutDefinitions().start();

        processEngine.registerDefinition(definition(type));

        assertNotNull(processEngine.newProcess(type).start());
    }

    @Test
    void shouldRejectDuplicateTypeRegisteredAfterBuild() {
        var type = ProcessType.of("DUPLICATE_LATE");
        var processEngine = new ProcessEngineBuilder(database)
            .withDispatcherEnabled(false)
            .addProcessDefinition(definition(type))
            .build();

        assertThrows(IllegalArgumentException.class, () -> processEngine.registerDefinition(definition(type)));
        assertEquals(1, new ProcessEngineAdmin(processEngine).listProcessSpecifications().size());
    }

    @Test
    void shouldRejectUnknownWorkerOnLateRegistration() {
        var processEngine = newEngineWithoutDefinitions();
        ProcessDefinition definition = builder -> builder
            .type(ProcessType.of("UNKNOWN_WORKER"))
            .initialState(TestState.INIT)
            .step(TestState.INIT, new WorkerId("missing"), context -> {}, TestState.DONE)
            .terminal(TestState.DONE)
            .build();

        var exception = assertThrows(IllegalArgumentException.class,
                () -> processEngine.registerDefinition(definition));

        assertEquals("Worker is not registered: " + new WorkerId("missing"), exception.getMessage());
        assertEquals(0, new ProcessEngineAdmin(processEngine).listProcessSpecifications().size());
    }

    @Test
    void runtimeOperationsShouldRequireStartedEngine() {
        var type = ProcessType.of("NOT_STARTED");
        var processEngine = newEngineWithoutDefinitions().registerDefinition(definition(type));

        assertThrows(IllegalStateException.class, () -> processEngine.newProcess(type));
        assertThrows(IllegalStateException.class, () -> processEngine.resumeFailedProcess(java.util.UUID.randomUUID()));
        assertFalse(processEngine.isStarted());
    }

    private ProcessEngine newEngineWithoutDefinitions() {
        return new ProcessEngineBuilder(database)
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .build();
    }

    @Test
    void instanceConfigurationShouldRejectUnsafeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessEngineBuilder(database).withInstanceHeartbeatInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessEngineBuilder(database)
                    .withInstanceTimeout(Duration.ofSeconds(59))
                    .withInstanceHeartbeatInterval(Duration.ofSeconds(15))
                    .build());
    }

    @Test
    void builderShouldResolveDialectOnce() {
        var countingDatabase = new CountingDatabase(database.getDataSource());
        new ProcessEngineBuilder(countingDatabase)
            .withDispatcherEnabled(false)
            .build();
        assertEquals(1, countingDatabase.dataSourceAccesses);
    }

    private ProcessDefinition definition(ProcessType type) {
        return builder -> builder
            .type(type)
            .initialState(TestState.INIT)
            .step(TestState.INIT, context -> {}, TestState.DONE)
            .terminal(TestState.DONE)
            .build();
    }

    private record ConfigurableDefinition(ProcessType type) implements ProcessDefinition {
        @Override
        public io.github.neherim.ruslo.core.api.definition.ProcessSpec define(
                io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder builder
        ) {
            return builder
                .type(type)
                .initialState(TestState.INIT)
                .step(TestState.INIT, context -> {}, TestState.DONE)
                .terminal(TestState.DONE)
                .build();
        }
    }

    private enum TestState implements ProcessState {
        INIT,
        DONE
    }

    private static final class CountingDatabase extends PlainJdbcDatabase {
        private int dataSourceAccesses;

        private CountingDatabase(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public DataSource getDataSource() {
            dataSourceAccesses++;
            return super.getDataSource();
        }
    }
}
