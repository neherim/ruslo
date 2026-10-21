package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.api.exception.TooManyRowsException;
import io.github.neherim.ruslo.core.definitions.SimpleOneStepProcessDefinition;
import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import org.h2.util.DateTimeUtils;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import static io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition.State.FINISH;
import static io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition.State.INIT;
import static org.junit.jupiter.api.Assertions.*;

class ProcessSearchTest extends BaseTest {

    private static final ProcessType ONE_STEP_TYPE = ProcessType.of("simple_one_step");

    @Test
    void listReturnsEmptyWhenNoProcessesExist() {
        var processEngine = simpleProcessEngine();

        assertTrue(processEngine.processes().list().isEmpty());
    }

    @Test
    void findByIdReturnsSingleProcess() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();

        var result = processEngine.processes().byId(processId).single();

        assertTrue(result.isPresent());
        assertEquals(processId, result.get().id());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        var processEngine = getDefaultProcessEngine();

        assertTrue(processEngine.processes().byId(UUID.randomUUID()).single().isEmpty());
    }

    @Test
    void findByBusinessKeyReturnsSingleProcess() {
        var processEngine = simpleProcessEngine();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).businessKey("order-123").start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).businessKey("order-456").start();

        var result = processEngine.processes().byBusinessKey("order-123").single();

        assertTrue(result.isPresent());
        assertEquals("order-123", result.get().businessKey().orElseThrow());
    }

    @Test
    void findByCorrelationIdReturnsSingleProcess() {
        var processEngine = simpleProcessEngine();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).correlationId("correlation-123").start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).correlationId("correlation-456").start();

        var result = processEngine.processes().byCorrelationId("correlation-123").singleDetails();

        assertTrue(result.isPresent());
        assertEquals("correlation-123", result.get().correlationId());
    }

    @Test
    void findByTypeFiltersProcesses() {
        var processEngine = processEngineWith(new SimpleProcessDefinition(), new SimpleOneStepProcessDefinition());
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        processEngine.newProcess(ONE_STEP_TYPE).start();

        var result = processEngine.processes().byType(SimpleProcessDefinition.TYPE).list();

        assertEquals(1, result.size());
        assertEquals(SimpleProcessDefinition.TYPE, result.get(0).type());
    }

    @Test
    void findByTypeNameFiltersProcesses() {
        var processEngine = processEngineWith(new SimpleProcessDefinition(), new SimpleOneStepProcessDefinition());
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        processEngine.newProcess(ONE_STEP_TYPE).start();

        var result = processEngine.processes().byTypeName("simple_one_step").list();

        assertEquals(1, result.size());
        assertEquals("simple_one_step", result.get(0).type().name());
    }

    @Test
    void findByStateFiltersProcesses() {
        var processEngine = simpleProcessEngine();
        var process1 = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        var process2 = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        emulator(processEngine).runUntilStop(process1); // INIT → FINISH

        var initProcesses = processEngine.processes().byState(INIT).list();
        var finishProcesses = processEngine.processes().byState(FINISH).list();

        assertEquals(1, initProcesses.size());
        assertEquals(process2, initProcesses.get(0).id());
        assertEquals(1, finishProcesses.size());
        assertEquals(process1, finishProcesses.get(0).id());
    }

    @Test
    void findByStateNameFiltersProcesses() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        emulator(processEngine).runUntilStop(processId); // INIT → FINISH

        var result = processEngine.processes().byStateName("FINISH").list();

        assertEquals(1, result.size());
        assertEquals(processId, result.get(0).id());
    }

    @Test
    void findByCompletedTrueReturnsOnlyCompletedProcesses() {
        var processEngine = processEngineWith(new SimpleOneStepProcessDefinition());
        var completedId = processEngine.newProcess(ONE_STEP_TYPE).start();
        processEngine.newProcess(ONE_STEP_TYPE).start();
        emulator(processEngine).runUntilStop(completedId);

        var result = processEngine.processes()
            .byStatus(io.github.neherim.ruslo.core.api.view.ProcessStatus.COMPLETED)
            .list();

        assertEquals(1, result.size());
        assertEquals(completedId, result.get(0).id());
        assertEquals(io.github.neherim.ruslo.core.api.view.ProcessStatus.COMPLETED, result.get(0).status());
    }

    @Test
    void findByCompletedFalseReturnsOnlyRunningProcesses() {
        var processEngine = processEngineWith(new SimpleOneStepProcessDefinition());
        var completedId = processEngine.newProcess(ONE_STEP_TYPE).start();
        var runningId = processEngine.newProcess(ONE_STEP_TYPE).start();
        emulator(processEngine).runUntilStop(completedId);

        var result = processEngine.processes()
            .byStatus(io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE)
            .list();

        assertEquals(1, result.size());
        assertEquals(runningId, result.get(0).id());
        assertEquals(io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE, result.get(0).status());
    }

    @Test
    void findByAttemptedReturnsOnlyProcessesWithAttempts() {
        var processEngine = simpleProcessEngine();
        var attemptedId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        database.update("update xs_process_instance set attempt = 1 where id = ?", attemptedId);

        var result = processEngine.processes().byAttempted().list();

        assertEquals(1, result.size());
        assertEquals(attemptedId, result.get(0).id());
        assertEquals(1, result.get(0).attempt());
    }

    @Test
    void singleDetailsReturnsLastError() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        database.update("update xs_process_instance set last_error = ? where id = ?",
                "IllegalStateException: boom", processId);

        var result = processEngine.processes().byId(processId).singleDetails();

        assertTrue(result.isPresent());
        assertEquals("IllegalStateException: boom", result.get().lastError().orElseThrow());
    }

    @Test
    void listWithLimitAndOffsetReturnsPaginatedResults() {
        var processEngine = simpleProcessEngine();
        for (int i = 0; i < 3; i++) {
            processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        }

        var page1 = processEngine.processes().list(2, 0);
        var page2 = processEngine.processes().list(2, 2);

        assertEquals(2, page1.size());
        assertEquals(1, page2.size());
    }

    @Test
    void singleThrowsTooManyRowExceptionWhenMultipleFound() {
        var processEngine = simpleProcessEngine();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();

        assertThrows(TooManyRowsException.class, () -> processEngine.processes().single());
    }

    @Test
    void singleDetailsThrowsTooManyRowExceptionWhenMultipleFound() {
        var processEngine = simpleProcessEngine();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).start();

        assertThrows(TooManyRowsException.class, () -> processEngine.processes().singleDetails());
    }

    @Test
    void singleDetailsReturnsProcessWithDetails() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE)
            .businessKey("test-key")
            .start();

        var result = processEngine.processes().byId(processId).singleDetails();

        assertTrue(result.isPresent());
        assertEquals(processId, result.get().id());
        assertTrue(result.get().state().is(INIT));
        assertEquals("test-key", result.get().businessKey().orElseThrow());
        assertFalse(result.get().stateHistory().isEmpty());
        assertEquals(processEngine.processes().byId(processId).single().orElseThrow().createdAt(),
                result.get().createdAt());
    }

    @Test
    void variablesAreStoredAsSingleJsonObject() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE)
            .variable("key", "value")
            .variable("amount", 42)
            .start();

        String stored = database.queryForObject("select variables from xs_process_instance where id = ?",
                rs -> rs.getString(1), processId)
            .orElseThrow();
        var details = processEngine.processes().byId(processId).singleDetails().orElseThrow();

        assertTrue(stored.contains("\"key\":\"value\""), stored);
        assertTrue(stored.contains("\"amount\":42"), stored);
        assertEquals(Map.of("key", "\"value\"", "amount", "42"), details.variables().asJsonMap());
        assertEquals(details, processEngine.processes().byId(processId).singleDetails().orElseThrow());
    }

    @Test
    void detailsReadVariablesTyped() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE)
            .variable("amount", 42)
            .start();

        var variables = processEngine.processes().byId(processId).singleDetails().orElseThrow().variables();

        assertEquals(Optional.of(42), variables.get(VariableType.of("amount", Integer.class)));
        assertEquals(Optional.of(42L), variables.get(VariableType.of("amount", Long.class)));
        assertEquals(Optional.empty(), variables.get(VariableType.of("missing", String.class)));
    }

    @Test
    void findByVariablesLikeMatchesStringVariable() {
        var processEngine = simpleProcessEngine();
        var matchedProcessId = processEngine.newProcess(SimpleProcessDefinition.TYPE)
            .variable("key", "value")
            .start();
        processEngine.newProcess(SimpleProcessDefinition.TYPE).variable("key", "other").start();

        var result = processEngine.processes().byVariablesLike("%\"key\":\"value\"%").list();

        assertEquals(1, result.size());
        assertEquals(matchedProcessId, result.get(0).id());
    }

    @Test
    void searchRestoresRemovedStateNamesFromStorage() {
        var processEngine = simpleProcessEngine();
        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        database.update("update xs_process_instance set state = ? where id = ?", "REMOVED_STATE", processId);
        database.update("update xs_process_state_hist set state = ? where link_process_instance = ?",
                "REMOVED_STATE", processId);

        var summary = processEngine.processes().byId(processId).single();
        var details = processEngine.processes().byId(processId).singleDetails();

        assertTrue(summary.isPresent());
        assertEquals("REMOVED_STATE", summary.get().state().name());
        assertTrue(details.isPresent());
        assertEquals("REMOVED_STATE", details.get().state().name());
        assertEquals("REMOVED_STATE", details.get().stateHistory().get(0).state().name());
    }

    @Test
    void stateHistoryTimeDoesNotDependOnJvmTimeZone() {
        var defaultTimeZone = TimeZone.getDefault();
        try {
            setJvmTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            var processEngine = simpleProcessEngine();
            var before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
            var after = Instant.now();

            setJvmTimeZone(TimeZone.getTimeZone("America/New_York"));
            var changedAt = processEngine.processes()
                .byId(processId)
                .singleDetails()
                .orElseThrow()
                .stateHistory()
                .get(0)
                .changedAt();

            assertFalse(changedAt.isBefore(before), changedAt + " is before " + before);
            assertFalse(changedAt.isAfter(after), changedAt + " is after " + after);
        } finally {
            setJvmTimeZone(defaultTimeZone);
        }
    }

    @Test
    void singleDetailsReturnsEmptyWhenNotFound() {
        var processEngine = getDefaultProcessEngine();

        assertTrue(processEngine.processes().byId(UUID.randomUUID()).singleDetails().isEmpty());
    }

    @Test
    void findByCreatedAtRangeFiltersProcessesAndReturnsCreationTime() {
        var processEngine = simpleProcessEngine();
        var oldProcessId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        var matchedProcessId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        var oldCreatedAt = Instant.parse("2026-05-01T10:00:00Z");
        var matchedCreatedAt = Instant.parse("2026-05-02T10:00:00Z");
        database.update("update xs_process_instance set created_at = ? where id = ?",
                Timestamp.from(oldCreatedAt), oldProcessId);
        database.update("update xs_process_instance set created_at = ? where id = ?",
                Timestamp.from(matchedCreatedAt), matchedProcessId);

        var result = processEngine.processes()
            .byCreatedAtFrom(Instant.parse("2026-05-02T00:00:00Z"))
            .byCreatedAtTo(Instant.parse("2026-05-02T23:59:59Z"))
            .list();

        assertEquals(1, result.size());
        assertEquals(matchedProcessId, result.get(0).id());
        assertEquals(matchedCreatedAt, result.get(0).createdAt());
    }

    @Test
    void findByNextRunAtRangeFiltersProcessesAndReturnsNextRun() {
        var processEngine = simpleProcessEngine();
        var laterProcessId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        var matchedProcessId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        var laterNextRunAt = Instant.parse("2026-05-03T10:00:00Z");
        var matchedNextRunAt = Instant.parse("2026-05-02T10:00:00Z");
        database.update("update xs_process_instance set next_run_at = ? where id = ?",
                Timestamp.from(laterNextRunAt), laterProcessId);
        database.update("update xs_process_instance set next_run_at = ? where id = ?",
                Timestamp.from(matchedNextRunAt), matchedProcessId);

        var result = processEngine.processes()
            .byNextRunAtFrom(Instant.parse("2026-05-02T00:00:00Z"))
            .byNextRunAtTo(Instant.parse("2026-05-02T23:59:59Z"))
            .list();

        assertEquals(1, result.size());
        assertEquals(matchedProcessId, result.get(0).id());
        assertEquals(Optional.of(matchedNextRunAt), result.get(0).nextRunAt());
    }

    /** Changes the JVM time zone; H2 caches it, so the cache is reset explicitly. */
    private static void setJvmTimeZone(TimeZone timeZone) {
        TimeZone.setDefault(timeZone);
        DateTimeUtils.resetCalendar();
    }

    private ProcessEngine simpleProcessEngine() {
        return processEngineWith(new SimpleProcessDefinition());
    }

    private ProcessEngine processEngineWith(ProcessDefinition... definitions) {
        return newProcessEngineWith(definitions);
    }
}
