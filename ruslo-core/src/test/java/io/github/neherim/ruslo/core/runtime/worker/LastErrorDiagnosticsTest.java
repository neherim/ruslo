package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LastErrorDiagnosticsTest extends BaseTest {
    @Test
    void failedActionPersistsErrorAndResumeClearsIt() {
        var fail = new AtomicBoolean(true);
        var fixture = WorkerTestFixture.with(database, definition(fail));
        var process = fixture.insertNewProcess(ProcessType.of("ERROR_DIAGNOSTICS"), "correlation");
        fixture.transactionalWorker().tryExecute(fixture.task(process));
        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(ProcessStatus.FAILED, stored.getStatus());
        assertEquals(1, stored.getAttempt());
        assertTrue(stored.getLastError().orElseThrow().contains("step failed"));
        fail.set(false);
        stored.resume();
        fixture.dao().update(stored);
        fixture.transactionalWorker().tryExecute(fixture.task(process));
        var completed = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(ProcessStatus.COMPLETED, completed.getStatus());
        assertEquals(0, completed.getAttempt());
        assertTrue(completed.getLastError().isEmpty());
    }

    @Test
    void systemRetryPostponesNextRun() {
        var fixture = WorkerTestFixture.with(database, definition(new AtomicBoolean(false)));
        var process = fixture.insertNewProcess(ProcessType.of("ERROR_DIAGNOSTICS"), "correlation");
        var task = fixture.task(process);
        var nextRun = fixture.dao().findById(process.getId()).orElseThrow().getNextRunAt().orElseThrow();
        fixture.stepErrorHandler().handleSystemError(new IllegalStateException("system failed"), task);
        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertTrue(stored.getNextRunAt().orElseThrow().isAfter(nextRun));
        assertEquals(1, stored.getAttempt());
    }

    @Test
    void unreadableProcessIsPostponedAndReleased() {
        var fixture = WorkerTestFixture.with(database, definition(new AtomicBoolean(false)));
        var process = fixture.insertNewProcess(ProcessType.of("ERROR_DIAGNOSTICS"), "correlation");
        var task = fixture.task(process);
        database.update("update xs_process_instance set type = 'REMOVED_TYPE' where id = ?", process.getId());
        var before = Instant.now();
        fixture.transactionalWorker().tryExecute(task);
        var row = database.queryForObject(
                "select attempt, last_error, next_run_at, lease_owner from xs_process_instance where id = ?",
                rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getObject(4)},
                process.getId())
            .orElseThrow();
        assertEquals(1, row[0]);
        assertTrue(((String) row[1]).contains("REMOVED_TYPE"), (String) row[1]);
        assertFalse(((Instant) row[2]).isBefore(before.plus(Duration.ofSeconds(59))));
        assertNull(row[3]);
    }

    @Test
    void failingErrorHandlerSchedulesRetry() {
        ProcessDefinition definition = builder -> builder.type("ERROR_HANDLER_FAILS")
            .onError((context, error) -> {
                throw new IllegalStateException("handler failed");
            })
            .initialState(State.START)
            .step(State.START, context -> {
                throw new IllegalStateException("step failed");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(ProcessType.of("ERROR_HANDLER_FAILS"), "correlation");
        fixture.transactionalWorker().tryExecute(fixture.task(process));
        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(ProcessStatus.ACTIVE, stored.getStatus());
        assertEquals(1, stored.getAttempt());
        assertTrue(stored.getLastError().orElseThrow().contains("step failed"));
        assertTrue(stored.getNextRunAt().orElseThrow().isAfter(Instant.now().plus(Duration.ofSeconds(59))));
    }

    private ProcessDefinition definition(AtomicBoolean fail) {
        return builder -> builder.type("ERROR_DIAGNOSTICS")
            .initialState(State.START)
            .step(State.START, context -> {
                if (fail.get()) throw new IllegalStateException("step failed");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
    }

    private enum State implements ProcessState {
        START,
        DONE
    }
}
