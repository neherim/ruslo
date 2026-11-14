package io.github.neherim.ruslo.admin.service;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.dto.BulkProcessSnapshot.Selection;
import io.github.neherim.ruslo.core.api.*;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.RowMapper;
import io.github.neherim.ruslo.spring.integration.SpringJdbcDatabase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkProcessAdminServiceTest {
    private static final ProcessType TYPE = ProcessType.of("BULK_TEST");
    private static final VariableType<String> GROUP = VariableType.of("group", String.class);
    private TestDatabase database;
    private ProcessEngine engine;
    private ProcessEngineAdmin admin;
    private BulkProcessAdminService service;

    private enum State implements ProcessState {
        START,
        NEXT,
        DONE
    }

    @BeforeEach
    void setUp() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:bulk-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        database = new TestDatabase(source);
        engine = new ProcessEngineBuilder(database).addProcessDefinition(BulkProcessAdminServiceTest::define)
            .withDispatcherEnabled(false)
            .build()
            .start();
        admin = new ProcessEngineAdmin(engine);
        service = new BulkProcessAdminService(engine, admin, database);
    }

    @AfterEach
    void close() {
        engine.close();
    }

    private static ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
            .initialState(State.START)
            .step(State.START, BulkProcessAdminServiceTest::step, State.NEXT)
            .step(State.NEXT, BulkProcessAdminServiceTest::step, State.DONE)
            .terminal(State.DONE)
            .build();
    }

    private static void step(ProcessContext context) {
        throw new AssertionError("Bulk operations must not execute handlers");
    }

    @Test
    void retriesMatchesBeyondTheCurrentPage() {
        failed();
        failed();
        var snapshot = service.prepare(BulkProcessOperation.RETRY, filter(ProcessStatus.FAILED));
        assertThat(snapshot.processes()).hasSize(2);

        var result = service.retry(snapshot);

        assertThat(result.succeededCount()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();
        assertThat(result.interruptedProcessId()).isNull();
        assertThat(engine.processes().byStatus(ProcessStatus.ACTIVE).list()).hasSize(2);
    }

    @Test
    void skipsChangedMissingAndNoLongerMatchingProcessesAndDoesNotIncludeNewMatches() {
        var changedState = failed();
        var changedStatus = failed();
        var changedFilter = failed();
        var deleted = failed();
        var unchanged = failed();
        var snapshot = service.prepare(BulkProcessOperation.RETRY, filter(ProcessStatus.FAILED));
        admin.moveProcessToState(changedState, State.NEXT);
        engine.resumeFailedProcess(changedStatus);
        admin.setProcessVariable(changedFilter, GROUP, "different");
        database.update("delete from xs_process_instance where id = ?", deleted);
        var later = failed();

        var result = service.retry(snapshot);

        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.skipped()).extracting(BulkProcessResult.Skipped::reason)
            .containsExactlyInAnyOrder("PROCESS_CHANGED", "PROCESS_CHANGED", "FILTER_NO_LONGER_MATCHES",
                    "PROCESS_MISSING");
        assertThat(status(unchanged)).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(status(later)).isEqualTo(ProcessStatus.FAILED);
        assertThat(status(changedFilter)).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void commitsEarlierChangesAndStopsAtSystemError() {
        var ids = List.of(failed(), failed(), failed());
        var snapshot = ordered(ids);
        database.failUpdate = ids.get(1);

        var result = service.retry(snapshot);

        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.interruptedProcessId()).isEqualTo(ids.get(1));
        assertThat(result.notAttemptedCount()).isEqualTo(1);
        assertThat(status(ids.get(0))).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(status(ids.get(1))).isEqualTo(ProcessStatus.FAILED);
        assertThat(status(ids.get(2))).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void skipsCompletedProcessAndMovesActiveProcess() {
        var completed = engine.newProcess(TYPE).variable(GROUP, "selected").start();
        admin.moveProcessToState(completed, State.DONE);
        var active = engine.newProcess(TYPE).variable(GROUP, "selected").start();
        var snapshot = new BulkProcessSnapshot(filter(null).forBulk(), Instant.now().toString(),
                List.of(selection(completed), selection(active)));

        var result = service.changeState(snapshot, State.DONE.name());

        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.skipped()).extracting(BulkProcessResult.Skipped::reason)
            .containsExactly("STATUS_NOT_APPLICABLE");
        assertThat(status(active)).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void holdsLockBetweenSnapshotCheckAndMutation() throws Exception {
        var id = failed();
        var snapshot = ordered(List.of(id));
        database.pauseLock = id;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var bulk = executor.submit(() -> service.retry(snapshot));
            assertThat(database.locked.await(10, TimeUnit.SECONDS)).isTrue();
            var contender = executor.submit(() -> {
                admin.moveProcessToState(id, State.NEXT);
                return true;
            });
            try {
                assertThrows(TimeoutException.class, () -> contender.get(1, TimeUnit.SECONDS));
            } finally {
                database.release.countDown();
            }
            assertThat(bulk.get(10, TimeUnit.SECONDS).succeededCount()).isEqualTo(1);
            assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(engine.processes().byId(id).single().orElseThrow().state().name()).isEqualTo(State.NEXT.name());
        } finally {
            database.release.countDown();
        }
    }

    private UUID failed() {
        var id = engine.newProcess(TYPE).variable(GROUP, "selected").start();
        database.update(
                "update xs_process_instance set status = 'FAILED', next_run_at = null, attempt = 3 where id = ?", id);
        return id;
    }

    private ProcessStatus status(UUID id) {
        return engine.processes().byId(id).single().orElseThrow().status();
    }

    private Selection selection(UUID id) {
        return Selection.of(engine.processes().byId(id).single().orElseThrow());
    }

    private BulkProcessSnapshot ordered(List<UUID> ids) {
        return new BulkProcessSnapshot(filter(ProcessStatus.FAILED).forBulk(), Instant.now().toString(),
                ids.stream().map(this::selection).toList());
    }

    private static ProcessSearchRequest filter(ProcessStatus status) {
        return new ProcessSearchRequest(null, TYPE.name(), null, status, null, null,
                null, null, null, null, "%selected%", 1, 500, null);
    }

    private static final class TestDatabase extends SpringJdbcDatabase {
        private UUID failUpdate;
        private UUID pauseLock;
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private TestDatabase(DataSource source) {
            super(new DataSourceTransactionManager(source), source);
        }

        @Override
        public int update(String sql, Object... params) {
            if (failUpdate != null && Arrays.asList(params).contains(failUpdate))
                throw new IllegalStateException("Test database failure");
            return super.update(sql, params);
        }

        @Override
        public <T> List<T> queryForList(String sql, RowMapper<T> mapper, Object... params) {
            var result = super.queryForList(sql, mapper, params);
            if (pauseLock != null && sql.endsWith("for update")
                    && Arrays.asList(params).containsAll(List.of(pauseLock, ProcessStatus.FAILED.name()))) {
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock test timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            return result;
        }

    }
}
