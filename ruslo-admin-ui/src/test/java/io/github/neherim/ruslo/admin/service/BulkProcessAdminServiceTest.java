package io.github.neherim.ruslo.admin.service;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.dto.BulkProcessSnapshot.Selection;
import io.github.neherim.ruslo.core.api.*;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.RowMapper;
import io.github.neherim.ruslo.spring.integration.SpringJdbcDatabase;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.IntStream;

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
    void setUp() throws Exception {
        var source = AdminTestDatabases.create();
        database = new TestDatabase(new SpringJdbcDatabase(new DataSourceTransactionManager(source), source));
        engine = new ProcessEngineBuilder(database).addProcessDefinition(BulkProcessAdminServiceTest::define)
            .withDispatcherEnabled(false)
            .build()
            .start();
        admin = new ProcessEngineAdmin(engine);
        service = new BulkProcessAdminService(engine, admin, database);
    }

    @AfterEach
    void close() throws Exception {
        engine.close();
        ((AutoCloseable) database.getDataSource()).close();
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
    void retriesAllPagesWithoutSizeLimitAndUsesTwoSelectsPerSuccess() {
        IntStream.range(0, 1005).forEach(i -> failed());
        var snapshot = service.prepare(BulkProcessOperation.RETRY, filter(ProcessStatus.FAILED));
        assertThat(snapshot.processes()).hasSize(1005);
        assertThat(snapshot.preview()).hasSize(20);
        database.resetCounts();

        var result = service.retry(snapshot);

        assertThat(result.succeededCount()).isEqualTo(1005);
        assertThat(result.skipped()).isEmpty();
        assertThat(result.interruption()).isNull();
        assertThat(database.selects).isEqualTo(2010);
        assertThat(database.updates).isEqualTo(1005);
        assertThat(database.transactions).isEqualTo(1005);
        assertThat(engine.processes().byStatus(ProcessStatus.ACTIVE).list()).hasSize(1005);
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
    void rejectsBulkExecutionInsideSpringTransactionBeforeWriting() {
        var id = failed();
        var retry = ordered(List.of(id));
        var move = service.prepare(BulkProcessOperation.CHANGE_STATE, filter(null));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database.getDataSource()));
        database.resetCounts();

        transaction.executeWithoutResult(status -> {
            assertThatIllegalArgumentException().isThrownBy(() -> service.retry(retry))
                .withMessageContaining("active Spring transaction");
            assertThatIllegalArgumentException().isThrownBy(() -> service.changeState(move, State.NEXT.name()))
                .withMessageContaining("active Spring transaction");
        });

        assertThat(database.updates).isZero();
        assertThat(status(id)).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void commitsEarlierChangesAndStopsAtSystemError() {
        var ids = List.of(failed(), failed(), failed());
        var snapshot = ordered(ids);
        database.failUpdate = ids.get(1);

        var result = service.retry(snapshot);

        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.interruption().processId()).isEqualTo(ids.get(1));
        assertThat(result.notAttemptedCount()).isEqualTo(1);
        assertThat(status(ids.get(0))).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(status(ids.get(1))).isEqualTo(ProcessStatus.FAILED);
        assertThat(status(ids.get(2))).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void reportsAfterCommitFailureAsUnknownWithoutRetryingCommittedProcess() {
        var ids = List.of(failed(), failed(), failed());
        var snapshot = ordered(ids);
        database.failAfterCommit = ids.get(1);

        var result = service.retry(snapshot);

        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.interruption().processId()).isEqualTo(ids.get(1));
        assertThat(result.notAttemptedCount()).isEqualTo(1);
        assertThat(status(ids.get(1))).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(status(ids.get(2))).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void rejectsInvalidTargetAndDuplicateOrMismatchedSelectionBeforeMutations() {
        var id = failed();
        var snapshot = service.prepare(BulkProcessOperation.CHANGE_STATE, filter(null));
        assertThatThrownBy(() -> service.changeState(snapshot, "UNDECLARED"))
            .isInstanceOf(IllegalArgumentException.class);
        var duplicate = new BulkProcessSnapshot(snapshot.filter(), snapshot.preparedAt(),
                List.of(snapshot.processes().getFirst(), snapshot.processes().getFirst()));
        assertThatThrownBy(() -> service.changeState(duplicate, State.NEXT.name()))
            .isInstanceOf(IllegalArgumentException.class);
        var mismatch = new BulkProcessSnapshot(snapshot.filter(), snapshot.preparedAt(),
                List.of(new Selection(id, "ANOTHER_TYPE", State.START.name(), ProcessStatus.FAILED)));
        assertThatThrownBy(() -> service.changeState(mismatch, State.NEXT.name()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(status(id)).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void checksActualTypeEvenWhenSnapshotClaimsTheSelectedType() {
        var otherType = ProcessType.of("OTHER");
        engine.registerDefinition(builder -> builder.type(otherType)
            .initialState(State.START)
            .step(State.START, BulkProcessAdminServiceTest::step, State.NEXT)
            .terminal(State.NEXT)
            .build());
        var otherId = engine.newProcess(otherType).start();
        var snapshot = new BulkProcessSnapshot(filter(null).forBulk(), Instant.now().toString(),
                List.of(new Selection(otherId, TYPE.name(), State.START.name(), ProcessStatus.ACTIVE)));

        var result = service.changeState(snapshot, State.NEXT.name());

        assertThat(result.succeededCount()).isZero();
        assertThat(result.skipped()).extracting(BulkProcessResult.Skipped::reason).containsExactly("PROCESS_CHANGED");
        assertThat(engine.processes().byId(otherId).single().orElseThrow().state().name())
            .isEqualTo(State.START.name());
    }

    @Test
    void completedProcessIsSkippedWithoutPoisoningNextTransactionAndTerminalMoveRecordsHistory() {
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
        assertThat(engine.processes().byId(active).singleDetails().orElseThrow().stateHistory()).hasSize(2);
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

    @Test
    void instantParametersWorkInUpdatesQueriesAndBatches() {
        var first = Instant.parse("2026-01-10T12:34:56.123456Z");
        database.update("create table app_times (id int primary key, moment timestamp with time zone)");
        database.update("insert into app_times values (?, ?)", 1, first);
        database.batchInsert("insert into app_times values (?, ?)", List.of(
                new Object[]{2, first.plusSeconds(1)}, new Object[]{3, first.plusSeconds(2)}));
        assertThat(database.queryForObject("select id from app_times where moment = ?", rs -> rs.getInt(1), first))
            .contains(1);
        assertThat(database.queryForObject("select moment from app_times where id = 3",
                rs -> rs.getTimestamp(1).toInstant()))
            .contains(first.plusSeconds(2));
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

    private static final class TestDatabase implements Database {
        private final Database delegate;
        private UUID failUpdate;
        private UUID failAfterCommit;
        private UUID pauseLock;
        private final AtomicBoolean paused = new AtomicBoolean();
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
        private long selects, updates, transactions;

        private TestDatabase(Database delegate) {
            this.delegate = delegate;
        }

        private void resetCounts() {
            selects = updates = transactions = 0;
        }

        @Override
        public <T> T doInTransactionWithResult(Supplier<T> action) {
            int currentDepth = depth.get();
            if (currentDepth == 0) transactions++;
            depth.set(currentDepth + 1);
            try {
                return delegate.doInTransactionWithResult(action);
            } finally {
                depth.set(currentDepth);
            }
        }

        @Override
        public int update(String sql, Object... params) {
            updates++;
            if (failUpdate != null && Arrays.asList(params).contains(failUpdate))
                throw new IllegalStateException("Test database failure");
            var updated = delegate.update(sql, params);
            if (failAfterCommit != null && Arrays.asList(params).contains(failAfterCommit)) {
                delegate.doAfterCommit(TestDatabase::failAfterCommit);
            }
            return updated;
        }

        private static void failAfterCommit() {
            throw new IllegalStateException("Test after-commit failure");
        }

        @Override
        public void batchInsert(String sql, List<Object[]> params) {
            delegate.batchInsert(sql, params);
        }

        @Override
        public <T> List<T> queryForList(String sql, RowMapper<T> mapper, Object... params) {
            selects++;
            var result = delegate.queryForList(sql, mapper, params);
            if (sql.contains("order by") && sql.endsWith("for update") && Arrays.asList(params).contains(pauseLock)
                    && pauseLock != null && paused.compareAndSet(false, true)) {
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

        @Override
        public void doAfterCommit(Runnable action) {
            delegate.doAfterCommit(action);
        }

        @Override
        public DataSource getDataSource() {
            return delegate.getDataSource();
        }
    }
}
