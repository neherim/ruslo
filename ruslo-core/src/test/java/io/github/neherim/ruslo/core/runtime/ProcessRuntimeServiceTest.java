package io.github.neherim.ruslo.core.runtime;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchSignal;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceFactory;
import io.github.neherim.ruslo.core.runtime.worker.StepErrorHandler;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.search.ProcessSearchService;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessRuntimeServiceTest extends BaseTest {
    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void processStartSignalsDispatcherAfterCommit() {
        var signal = new ProcessDispatchSignal();
        var runtime = runtime(signal);

        database.doInTransaction(() -> {
            runtime.newProcess(SimpleProcessDefinition.TYPE).start();
            assertFalse(awaitSignal(signal));
        });

        assertTrue(awaitSignal(signal));
    }

    @Test
    void rolledBackProcessStartDoesNotSignalDispatcher() {
        var signal = new ProcessDispatchSignal();
        var runtime = runtime(signal);

        assertThrows(IllegalStateException.class, () -> database.doInTransaction(() -> {
            runtime.newProcess(SimpleProcessDefinition.TYPE).start();
            throw new IllegalStateException("synthetic rollback");
        }));

        assertFalse(awaitSignal(signal));
    }

    @Test
    void processStartShouldUseInjectedClockForAllPersistedTimestamps() {
        var initial = Instant.parse("2026-07-20T10:15:30Z");
        var clock = new CountingClock(initial);
        var runtime = runtime(new ProcessDispatchSignal(), clock);

        var processId = runtime.newProcess(SimpleProcessDefinition.TYPE).start();

        assertEquals(2, clock.reads);
        assertEquals(initial, timestamp("select scheduled_at from xs_process_instance where id = ?", processId));
        assertEquals(initial, timestamp(
                "select changed_at from xs_process_state_hist where link_process_instance = ?", processId));
        assertEquals(initial.plusSeconds(1), timestamp(
                "select create_at from xs_process_instance where id = ?", processId));
    }

    @Test
    void historyShouldHaveStableOrderForEqualClockInstants() {
        var runtime = runtime(new ProcessDispatchSignal(), Clock.fixed(
                Instant.parse("2026-07-20T10:15:30Z"), ZoneOffset.UTC));
        var processId = runtime.newProcess(SimpleProcessDefinition.TYPE).start();

        runtime.moveToState(processId, SimpleProcessDefinition.State.FINISH);

        var searchService = new ProcessSearchService(database, JsonUtils.DEFAULT_OBJECT_MAPPER);
        var states = searchService
                .processes().byId(processId).singleDetails().orElseThrow().stateHistory().stream()
                .map(entry -> entry.state().name())
                .toList();
        var repeatedStates = searchService
                .processes().byId(processId).singleDetails().orElseThrow().stateHistory().stream()
                .map(entry -> entry.state().name())
                .toList();

        assertEquals(states, repeatedStates);
        assertEquals(Set.of("INIT", "FINISH"), Set.copyOf(states));
    }

    private ProcessRuntimeService runtime(ProcessDispatchSignal signal) {
        return runtime(signal, CLOCK);
    }

    private ProcessRuntimeService runtime(ProcessDispatchSignal signal, Clock clock) {
        var registry = new ProcessDefinitionRegistry(List.of(new SimpleProcessDefinition()));
        var factory = new ProcessInstanceFactory(clock, JsonUtils.DEFAULT_OBJECT_MAPPER, registry);
        var dao = new ProcessInstanceDao(database, clock, factory, true,
                new io.github.neherim.ruslo.core.spi.ProcessEngineMetrics() {});
        ProcessEngineMetrics metrics = new ProcessEngineMetrics() {};
        var processMdc = new ProcessMdc(MdcKeys.defaults());
        var stepExecutor = new StepExecutor(processMdc, metrics, clock);
        return new ProcessRuntimeService(clock, database, dao, factory, signal,
                metrics, processMdc, stepExecutor, new StepErrorHandler(clock, database, dao, processMdc, metrics));
    }

    private Instant timestamp(String sql, java.util.UUID processId) {
        return database.queryForObject(sql, rs -> rs.getTimestamp(1).toInstant(), processId).orElseThrow();
    }

    private static boolean awaitSignal(ProcessDispatchSignal signal) {
        try {
            return signal.await(Duration.ZERO);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting process dispatch signal", ex);
        }
    }

    private static final class CountingClock extends Clock {
        private final Instant initial;
        private int reads;

        private CountingClock(Instant initial) {
            this.initial = initial;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return initial.plusSeconds(reads++);
        }
    }
}
