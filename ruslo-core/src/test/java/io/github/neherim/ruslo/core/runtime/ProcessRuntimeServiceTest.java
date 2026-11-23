package io.github.neherim.ruslo.core.runtime;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.RusloTimeSource;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchSignal;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceFactory;
import io.github.neherim.ruslo.core.runtime.worker.StepErrorHandler;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.search.ProcessSearchService;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.EngineTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRuntimeServiceTest extends BaseTest {
    private static final RusloTimeSource SYSTEM_TIME = Instant::now;

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
        var clock = new CountingTimeSource(initial.plusNanos(600));
        var runtime = runtime(new ProcessDispatchSignal(), clock);

        var processId = runtime.newProcess(SimpleProcessDefinition.TYPE).start();

        assertEquals(2, clock.reads);
        assertEquals(initial, timestamp("select next_run_at from xs_process_instance where id = ?", processId));
        assertEquals(initial, timestamp(
                "select changed_at from xs_process_state_hist where link_process_instance = ?", processId));
        assertEquals(initial.plusSeconds(1), timestamp(
                "select created_at from xs_process_instance where id = ?", processId));
    }

    @Test
    void historyShouldHaveStableOrderForEqualClockInstants() {
        var runtime = runtime(new ProcessDispatchSignal(), () -> Instant.parse("2026-07-20T10:15:30Z"));
        var processId = runtime.newProcess(SimpleProcessDefinition.TYPE).start();

        runtime.moveToState(processId, SimpleProcessDefinition.State.FINISH);

        var searchService = new ProcessSearchService(database,
                new ProcessDefinitionRegistry(List.of(new SimpleProcessDefinition())));
        var states = searchService
            .processes()
            .byId(processId)
            .singleDetails()
            .orElseThrow()
            .stateHistory()
            .stream()
            .map(entry -> entry.state().name())
            .toList();
        var repeatedStates = searchService
            .processes()
            .byId(processId)
            .singleDetails()
            .orElseThrow()
            .stateHistory()
            .stream()
            .map(entry -> entry.state().name())
            .toList();

        assertEquals(states, repeatedStates);
        assertEquals(Set.of("INIT", "FINISH"), Set.copyOf(states));
    }

    private ProcessRuntimeService runtime(ProcessDispatchSignal signal) {
        return runtime(signal, SYSTEM_TIME);
    }

    private ProcessRuntimeService runtime(ProcessDispatchSignal signal, RusloTimeSource timeSource) {
        var engineTime = new EngineTime(timeSource);
        var registry = new ProcessDefinitionRegistry(List.of(new SimpleProcessDefinition()));
        var factory = new ProcessInstanceFactory(engineTime, registry);
        var dao = new ProcessInstanceDao(database, engineTime, factory, true,
                new io.github.neherim.ruslo.core.spi.ProcessEngineMetrics() {
                });
        ProcessEngineMetrics metrics = new ProcessEngineMetrics() {
        };
        var processMdc = new ProcessMdc(MdcKeys.defaults());
        var stepExecutor = new StepExecutor(processMdc, metrics, engineTime);
        return new ProcessRuntimeService(engineTime, database, dao, factory, signal,
                metrics, processMdc, stepExecutor, new StepErrorHandler(engineTime, database, dao, processMdc, metrics));
    }

    private Instant timestamp(String sql, java.util.UUID processId) {
        return database.queryForObject(sql, rs -> rs.getTimestamp(1).toInstant(), processId).orElseThrow();
    }

    private static boolean awaitSignal(ProcessDispatchSignal signal) {
        try {
            return signal.await(0);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting process dispatch signal", ex);
        }
    }

    private static final class CountingTimeSource implements RusloTimeSource {
        private final Instant initial;
        private int reads;

        private CountingTimeSource(Instant initial) {
            this.initial = initial;
        }

        @Override
        public Instant now() {
            return initial.plusSeconds(reads++);
        }
    }
}
