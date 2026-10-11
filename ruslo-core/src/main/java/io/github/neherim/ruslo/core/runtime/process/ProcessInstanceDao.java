package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.api.view.ProcessTypeCounts;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseLostException;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;
import io.github.neherim.ruslo.core.utils.DateTimeUtils;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes process instances and their state history. */
public class ProcessInstanceDao {
    private static final String STATE_COLUMNS = """
            state, worker_id, status, variables, messages, attempt, last_error,
              scheduled_at, available_at, deadline_at""";

    private static final String SET_STATE = """
            state = ?, worker_id = ?, status = ?, variables = ?, messages = ?, attempt = ?, last_error = ?,
              scheduled_at = ?, available_at = ?, deadline_at = ?, row_version = row_version + 1, update_at = ?
            """;

    private static final String SELECT_PROCESS_SQL = "select id, business_key, type, correlation_id, row_version, "
            + STATE_COLUMNS + " from xs_process_instance where id = ?";

    private static final String SELECT_CLAIMED_PROCESS_SQL = SELECT_PROCESS_SQL + " and lease_owner = ?";

    private static final String FOR_UPDATE = " for update";

    private static final String INSERT_PROCESS_SQL = "insert into xs_process_instance (" + STATE_COLUMNS
            + ", create_at, id, business_key, type, correlation_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_PROCESS_SQL = "update xs_process_instance set " + SET_STATE + " where id = ?";

    private static final String UPDATE_CLAIMED_PROCESS_SQL = UPDATE_PROCESS_SQL
            + " and lease_owner = ? and row_version = ?";

    private static final String UPDATE_AND_RELEASE_CLAIMED_PROCESS_SQL = "update xs_process_instance set "
            + "lease_owner = null, " + SET_STATE + " where id = ? and lease_owner = ? and row_version = ?";

    private static final String POSTPONE_CLAIMED_PROCESS_SQL = """
            update xs_process_instance set
              attempt = attempt + 1,
              last_error = ?,
              available_at = ?,
              lease_owner = null,
              row_version = row_version + 1,
              update_at = ?
            where id = ? and lease_owner = ?
            """;

    private static final String COUNT_UNCOMPLETED_PROCESSES_SQL = """
            select count(*) from xs_process_instance where status <> 'COMPLETED'
            """;

    private static final String COUNT_INCOMPLETE_BY_TYPE_SQL = """
            select type,
                   sum(case when status = 'ACTIVE' then 1 else 0 end) as active_count,
                   sum(case when status = 'FAILED' then 1 else 0 end) as failed_count,
                   sum(case when status = 'ACTIVE' and scheduled_at <= ? then 1 else 0 end) as overdue_count
            from xs_process_instance
            where status <> 'COMPLETED'
            group by type
            order by type
            """;

    private static final String COUNT_BY_STATUS_SQL = """
            select count(*) from xs_process_instance where status = ?
            """;

    private static final String READY_FOR_WORKER_CONDITION = """
            from xs_process_instance
            where worker_id = ? and status = 'ACTIVE' and lease_owner is null and available_at <= ?
            """;

    private static final String COUNT_READY_FOR_WORKER_SQL = "select count(*) " + READY_FOR_WORKER_CONDITION;

    private static final String OLDEST_READY_FOR_WORKER_SQL = "select min(available_at) " + READY_FOR_WORKER_CONDITION;

    private static final String INSERT_PROCESS_STATE_HIST_SQL = """
            insert into xs_process_state_hist (id, link_process_instance, state, changed_at)
            values (?, ?, ?, ?)
            """;

    private final Database database;
    private final Clock clock;
    private final ProcessInstanceFactory processInstanceFactory;
    private final boolean historyEnabled;
    private final ProcessEngineMetrics metrics;

    public ProcessInstanceDao(Database database, Clock clock, ProcessInstanceFactory processInstanceFactory,
                              boolean historyEnabled, ProcessEngineMetrics metrics) {
        this.database = database;
        this.clock = clock;
        this.processInstanceFactory = processInstanceFactory;
        this.historyEnabled = historyEnabled;
        this.metrics = metrics;
    }

    public Optional<ProcessInstance> findWithLockById(UUID id) {
        return database.queryForObject(SELECT_PROCESS_SQL + FOR_UPDATE, this::mapProcessRow, id);
    }

    public Optional<ProcessInstance> findById(UUID id) {
        return database.queryForObject(SELECT_PROCESS_SQL, this::mapProcessRow, id);
    }

    /**
     * Loads the process and locks its row if {@code lease} still owns it.
     *
     * @return the claimed snapshot, or empty if the lease is lost
     */
    public Optional<ClaimedProcess> findClaimedWithLock(ProcessLease lease) {
        return findClaimed(SELECT_CLAIMED_PROCESS_SQL + FOR_UPDATE, lease);
    }

    /**
     * Loads the process without a row lock if {@code lease} still owns it. Used by the non-transactional worker,
     * which holds no transaction while user code runs.
     *
     * @return the claimed snapshot, or empty if the lease is lost
     */
    public Optional<ClaimedProcess> findClaimed(ProcessLease lease) {
        return findClaimed(SELECT_CLAIMED_PROCESS_SQL, lease);
    }

    private Optional<ClaimedProcess> findClaimed(String sql, ProcessLease lease) {
        return database.queryForObject(sql, rs -> mapClaimedProcessRow(rs, lease),
                lease.processId(), lease.ownerId());
    }

    private ProcessInstance mapProcessRow(ResultSet rs) throws SQLException {
        return processInstanceFactory.loadFromStorage(
                rs.getObject("id", UUID.class),
                rs.getString("business_key"),
                rs.getString("type"),
                rs.getString("state"),
                rs.getString("correlation_id"),
                ProcessStatus.valueOf(rs.getString("status")),
                rs.getString("variables"),
                rs.getString("messages"),
                rs.getInt("attempt"),
                rs.getString("last_error"),
                DateTimeUtils.toInstant(rs.getTimestamp("scheduled_at")),
                DateTimeUtils.toInstant(rs.getTimestamp("available_at")),
                DateTimeUtils.toInstant(rs.getTimestamp("deadline_at")));
    }

    private ClaimedProcess mapClaimedProcessRow(ResultSet rs, ProcessLease lease) throws SQLException {
        return new ClaimedProcess(mapProcessRow(rs), lease, rs.getLong("row_version"));
    }

    /** Inserts a new process. */
    public void insert(ProcessInstance process) {
        database.doInTransaction(() -> {
            database.update(INSERT_PROCESS_SQL, stateParams(process, clock.instant(), process.getId(),
                    process.getBusinessKey().orElse(null), process.getType().name(), process.getCorrelationId()));
            recordTransitions(process);
        });
    }

    /** Stores the process state without changing the lease owner. */
    public void update(ProcessInstance process) {
        database.doInTransaction(() -> {
            var updated = database.update(UPDATE_PROCESS_SQL, stateParams(process, clock.instant(), process.getId()));
            if (updated != 1) {
                throw new IllegalArgumentException("Process not found in database: " + process.getId());
            }
            recordTransitions(process);
        });
    }

    /**
     * Stores the claimed process state and keeps its lease.
     *
     * @throws LeaseLostException if the lease is lost or the process was changed concurrently
     */
    public void updateClaimed(ClaimedProcess claimed) {
        database.doInTransaction(() -> updateClaimed(claimed, UPDATE_CLAIMED_PROCESS_SQL));
    }

    /**
     * Stores the claimed process state and releases its lease atomically.
     *
     * @throws LeaseLostException if the lease is lost or the process was changed concurrently
     */
    public void updateAndReleaseLease(ClaimedProcess claimed) {
        database.doInTransaction(() -> updateClaimed(claimed, UPDATE_AND_RELEASE_CLAIMED_PROCESS_SQL));
    }

    private void updateClaimed(ClaimedProcess claimed, String updateSql) {
        var process = claimed.process();
        var lease = claimed.lease();
        var updated = database.update(updateSql, stateParams(process, clock.instant(),
                process.getId(), lease.ownerId(), claimed.expectedRowVersion()));
        if (updated != 1) {
            throw new LeaseLostException(lease.processId());
        }
        recordTransitions(process);
    }

    /**
     * Postpones the claimed process after a system error and releases its lease without loading the process,
     * so the error is handled even if the process cannot be read (e.g. an unknown type).
     *
     * @throws LeaseLostException if the lease is lost
     */
    public void postponeClaimed(ProcessLease lease, Instant availableAt, String error) {
        var updated = database.update(POSTPONE_CLAIMED_PROCESS_SQL,
                error, availableAt, clock.instant(), lease.processId(), lease.ownerId());
        if (updated != 1) {
            throw new LeaseLostException(lease.processId());
        }
    }

    /** Returns the state columns ({@link #STATE_COLUMNS}) followed by {@code tail}. */
    private static Object[] stateParams(ProcessInstance process, Object... tail) {
        var params = new ArrayList<Object>(Arrays.asList(
                process.getState().name(),
                process.getWorkerId().map(WorkerId::name).orElse(null),
                process.getStatus().name(),
                process.getVariables().serializeToString(),
                process.getMessages().serializeToString(),
                process.getAttempt(),
                process.getLastError().orElse(null),
                process.getScheduledAt().orElse(null),
                process.getAvailableAt().orElse(null),
                process.getDeadlineAt().orElse(null)));
        params.addAll(Arrays.asList(tail));
        return params.toArray();
    }

    /** Returns the number of {@code ACTIVE} and {@code FAILED} processes. */
    public long countIncompleteProcesses() {
        return database.queryForObject(COUNT_UNCOMPLETED_PROCESSES_SQL, rs -> rs.getLong(1))
                .orElse(0L);
    }

    /**
     * Counts incomplete processes per type in one query, ordered by type name.
     *
     * @param overdueAt an {@code ACTIVE} process with {@code scheduled_at <= overdueAt} counts as overdue
     */
    public List<ProcessTypeCounts> countIncompleteByType(Instant overdueAt) {
        return database.queryForList(COUNT_INCOMPLETE_BY_TYPE_SQL, rs -> new ProcessTypeCounts(
                new ProcessTypeId(rs.getString("type")),
                rs.getLong("active_count"),
                rs.getLong("failed_count"),
                rs.getLong("overdue_count")), overdueAt);
    }

    /** Returns the number of processes in the status. */
    public long countByStatus(ProcessStatus status) {
        return database.queryForObject(COUNT_BY_STATUS_SQL, rs -> rs.getLong(1), status.name()).orElse(0L);
    }

    /** Returns the number of ready processes of the worker that no lease holds. */
    public long countReadyForWorker(WorkerId workerId) {
        return database.queryForObject(COUNT_READY_FOR_WORKER_SQL, rs -> rs.getLong(1),
                workerId.name(), clock.instant()).orElse(0L);
    }

    /**
     * Returns how long the oldest ready unclaimed process of the worker has been waiting since {@code available_at};
     * zero if there is none.
     */
    public Duration oldestReadyAgeForWorker(WorkerId workerId) {
        var now = clock.instant();
        return database.queryForObject(OLDEST_READY_FOR_WORKER_SQL,
                        rs -> DateTimeUtils.toInstant(rs.getTimestamp(1)), workerId.name(), now)
                .map(oldest -> Duration.between(oldest, now))
                .filter(age -> !age.isNegative())
                .orElse(Duration.ZERO);
    }

    /** Stores new transitions in history and, after commit, counts entering a terminal state as completion. */
    private void recordTransitions(ProcessInstance process) {
        var entries = process.drainPendingHistoryEntries();
        var spec = process.getProcessSpec();
        if (entries.stream().anyMatch(entry -> spec.getStateSpec(entry.state()) instanceof ProcessSpec.TerminalState)) {
            database.doAfterCommit(() -> metrics.recordProcessCompleted(process.getType()));
        }
        if (!historyEnabled || entries.isEmpty()) return;
        var params = entries.stream()
                .map(e -> new Object[]{UUIDGenerator.generate(), process.getId(), e.state().name(),
                        e.changedAt()})
                .toList();
        database.batchInsert(INSERT_PROCESS_STATE_HIST_SQL, params);
    }
}
