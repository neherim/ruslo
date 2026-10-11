package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ProcessMetricsCommitTest extends BaseTest {
    private static final ProcessType TYPE = ProcessType.of("METRICS_COMMIT");
    private static final ProcessType FAILING = ProcessType.of("METRICS_FAILING");
    private static final MessageType<String> SIGNAL = MessageType.of("signal", String.class);

    @Test void completionMetricIsRecordedOnlyAfterCommitWhileHandlerMetricCountsEveryCall() {
        var metrics = new InMemoryProcessEngineMetrics();
        ProcessDefinition definition = builder -> builder.type(TYPE).initialState(State.START)
            .step(State.START, context -> { }, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL,
                (context, message) -> { }, State.DONE))
            .terminal(State.DONE).build();
        var engine = new ProcessEngineBuilder(database).addProcessDefinition(definition)
            .withMetrics(metrics).withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false).build().start();
        try {
            var id = engine.newProcess(TYPE).start();
            assertThrows(IllegalStateException.class, () -> database.doInTransaction(() -> {
                emulator(engine).runOneStep(id);
                assertEquals(1, metrics.getTotalStepsExecuted());
                throw new IllegalStateException("rollback");
            }));
            assertEquals(1, metrics.getTotalStepsExecuted());
            assertEquals(State.START.name(), engine.processes().byId(id).singleDetails().orElseThrow().state().name());

            emulator(engine).runOneStep(id);
            assertEquals(2, metrics.getTotalStepsExecuted());
            engine.sendMessage(id, SIGNAL, "ok");
            assertThrows(IllegalStateException.class, () -> database.doInTransaction(() -> {
                emulator(engine).runOneStep(id);
                assertEquals(0, metrics.getProcessCompletedCount(TYPE));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(0, metrics.getProcessCompletedCount(TYPE));
            assertEquals(3, metrics.getTotalStepsExecuted());
            assertEquals(ProcessStatus.ACTIVE, engine.processes().byId(id).singleDetails().orElseThrow().status());

            emulator(engine).runOneStep(id);
            assertEquals(1, metrics.getProcessCompletedCount(TYPE));
            assertEquals(4, metrics.getTotalStepsExecuted());
            assertEquals(2, metrics.getStepExecutionMetrics(TYPE, State.WAIT).getExecutionCount());

            var moved = engine.newProcess(TYPE).start();
            new ProcessEngineAdmin(engine).moveProcessToState(moved, State.DONE);
            assertEquals(2, metrics.getProcessCompletedCount(TYPE));
            assertEquals(4, metrics.getTotalStepsExecuted());
        } finally { engine.close(); }
    }

    @Test void handlerErrorIsRecordedAndGaugesFollowProcessStatus() {
        var metrics = new InMemoryProcessEngineMetrics();
        var clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
        ProcessDefinition definition = builder -> builder.type(FAILING)
            .onError((context, error) -> ErrorResult.fail()).initialState(State.START)
            .step(State.START, context -> fail(), State.DONE)
            .terminal(State.DONE).build();
        var engine = new ProcessEngineBuilder(database).addProcessDefinition(definition)
            .withMetrics(metrics).withClock(clock).withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false).build().start();
        try {
            var worker = metrics.getWorkerMetrics(WorkerId.DEFAULT.name());
            var id = engine.newProcess(FAILING).start();
            clock.advance(Duration.ofSeconds(5));
            assertEquals(1, metrics.getProcessCount(ProcessStatus.ACTIVE));
            assertEquals(0, metrics.getProcessCount(ProcessStatus.FAILED));
            assertEquals(1, worker.getReadyProcesses());
            assertEquals(Duration.ofSeconds(5), worker.getLag());

            emulator(engine, 10).runOneStep(id);

            assertEquals(0, metrics.getTotalStepsExecuted());
            assertEquals(1, metrics.getTotalStepsFailed());
            assertEquals(1, metrics.getProcessFailedCount(FAILING));
            assertEquals(0, metrics.getProcessCount(ProcessStatus.ACTIVE));
            assertEquals(1, metrics.getProcessCount(ProcessStatus.FAILED));
            assertEquals(0, worker.getReadyProcesses());
            assertEquals(Duration.ZERO, worker.getLag());
            assertEquals(0, metrics.getSystemErrorCount());
        } finally { engine.close(); }
    }

    private static void fail() {
        throw new IllegalStateException("handler failed");
    }

    private enum State implements ProcessState { START, WAIT, DONE }
    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
