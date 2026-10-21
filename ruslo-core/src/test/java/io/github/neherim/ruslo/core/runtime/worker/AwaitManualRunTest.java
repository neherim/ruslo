package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/** An await taken by {@code next_run_at} without a due event, as after a manual "run now" in SQL. */
class AwaitManualRunTest extends BaseTest {
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);

    @Test
    void workerOnlyRecomputesNextRunAndReleasesProcess() {
        ProcessDefinition definition = builder -> builder.type("MANUAL_RUN")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, State.DONE)
                .after(Duration.ofHours(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(ProcessType.of("MANUAL_RUN"), "correlation");
        var deadline = process.getAfterDueAt().orElseThrow().truncatedTo(ChronoUnit.MICROS);

        fixture.transactionalWorker().tryExecute(fixture.task(process));

        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals("WAIT", stored.getState().name());
        assertEquals(0, stored.getAttempt());
        assertTrue(stored.getLastError().isEmpty());
        assertEquals(deadline, stored.getNextRunAt().orElseThrow());
        assertEquals(1L, database.queryForObject(
                "select count(*) from xs_process_state_hist where link_process_instance = ?",
                rs -> rs.getLong(1), process.getId())
            .orElseThrow());
        assertEquals(0L, database.queryForObject(
                "select count(*) from xs_process_instance where id = ? and lease_owner is not null",
                rs -> rs.getLong(1), process.getId())
            .orElseThrow());
    }

    private enum State implements ProcessState {
        WAIT,
        DONE
    }
}
