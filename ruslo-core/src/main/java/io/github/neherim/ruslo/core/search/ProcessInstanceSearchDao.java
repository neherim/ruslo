package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails.StateHistoryEntry;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.api.view.ProcessTypeCounts;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.runtime.process.ProcessTimers;
import io.github.neherim.ruslo.core.runtime.process.ProcessVariables;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.utils.DateTimeUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Searches and filters process instances. */
public class ProcessInstanceSearchDao {
    private static final String SELECT_SUMMARIES_SQL = """
            select id, type, state, status, created_at, next_run_at, business_key, attempt
            from xs_process_instance where 1=1
            """;

    private static final String SELECT_DETAILS_SQL = """
            select id, type, state, status, created_at, next_run_at, business_key, attempt,
                   correlation_id, last_error, variables, timers
            from xs_process_instance where 1=1
            """;

    private static final String COUNT_INCOMPLETE_BY_TYPE_SQL = """
            select type,
                   sum(case when status = 'ACTIVE' then 1 else 0 end) as active_count,
                   sum(case when status = 'FAILED' then 1 else 0 end) as failed_count,
                   sum(case when status = 'ACTIVE' and next_run_at <= ? then 1 else 0 end) as overdue_count
            from xs_process_instance
            where status <> 'COMPLETED'
            group by type
            order by type
            """;

    private static final String SELECT_STATE_HIST_SQL = """
            select state, changed_at from xs_process_state_hist
            where link_process_instance = ?
            order by changed_at, id
            """;

    private final Database database;
    private final ProcessDefinitionRegistry processDefinitionRegistry;

    public ProcessInstanceSearchDao(
            Database database,
            ProcessDefinitionRegistry processDefinitionRegistry
    ) {
        this.database = database;
        this.processDefinitionRegistry = processDefinitionRegistry;
    }

    /**
     * Counts incomplete processes per type in one query, ordered by type name.
     *
     * @param overdueAt an {@code ACTIVE} process with {@code next_run_at <= overdueAt} counts as overdue
     */
    public List<ProcessTypeCounts> countIncompleteByType(Instant overdueAt) {
        return database.queryForList(COUNT_INCOMPLETE_BY_TYPE_SQL, rs -> new ProcessTypeCounts(
                processDefinitionRegistry.declaredType(rs.getString("type")),
                rs.getLong("active_count"),
                rs.getLong("failed_count"),
                rs.getLong("overdue_count")), overdueAt);
    }

    /**
     * Returns process summaries matching the criteria; {@code null} criteria fields do not filter.
     * Without {@code limit} all matching rows are returned.
     *
     * @param lock locks the returned rows ({@code for update})
     */
    public List<ProcessInstanceSummary> findSummaries(
            ProcessSearchCriteria criteria,
            Integer limit,
            Integer offset,
            boolean lock
    ) {
        var sql = new StringBuilder(SELECT_SUMMARIES_SQL);
        var params = new ArrayList<>();
        appendFilterClauses(sql, params, criteria);

        sql.append(" order by created_at desc");

        if (limit != null) {
            sql.append(" limit ?");
            params.add(limit);
        }
        if (offset != null) {
            sql.append(" offset ?");
            params.add(offset);
        }
        if (lock) {
            sql.append(" for update");
        }

        return database.queryForList(sql.toString(), this::mapSummaryRow, params.toArray());
    }

    /** Returns details of matching processes up to the limit in one joined transaction. */
    public List<ProcessInstanceDetails> findDetails(ProcessSearchCriteria criteria, int limit, boolean lock) {
        var sql = new StringBuilder(SELECT_DETAILS_SQL);
        var params = new ArrayList<>();
        appendFilterClauses(sql, params, criteria);

        sql.append(" limit ?");
        params.add(limit);
        if (lock) {
            sql.append(" for update");
        }

        // History queries in the row mapper must use the same connection as the main query.
        return database.doInTransactionWithResult(
                () -> database.queryForList(sql.toString(), this::mapDetailsRow, params.toArray()));
    }

    private void appendFilterClauses(StringBuilder sql, List<Object> params, ProcessSearchCriteria filter) {
        if (filter.processId() != null) {
            sql.append(" and id = ?");
            params.add(filter.processId());
        }
        if (filter.typeName() != null) {
            sql.append(" and type = ?");
            params.add(filter.typeName());
        }
        if (filter.stateName() != null) {
            sql.append(" and state = ?");
            params.add(filter.stateName());
        }
        if (filter.status() != null) {
            sql.append(" and status = ?");
            params.add(filter.status().name());
        }
        if (filter.attempted()) {
            sql.append(" and attempt > 0");
        }
        if (filter.businessKey() != null) {
            sql.append(" and business_key = ?");
            params.add(filter.businessKey());
        }
        if (filter.nextRunAtFrom() != null) {
            sql.append(" and next_run_at >= ?");
            params.add(Timestamp.from(filter.nextRunAtFrom()));
        }
        if (filter.nextRunAtTo() != null) {
            sql.append(" and next_run_at <= ?");
            params.add(Timestamp.from(filter.nextRunAtTo()));
        }
        if (filter.createdAtFrom() != null) {
            sql.append(" and created_at >= ?");
            params.add(Timestamp.from(filter.createdAtFrom()));
        }
        if (filter.createdAtTo() != null) {
            sql.append(" and created_at <= ?");
            params.add(Timestamp.from(filter.createdAtTo()));
        }
        if (filter.correlationId() != null) {
            sql.append(" and correlation_id = ?");
            params.add(filter.correlationId());
        }
        if (filter.variablesLike() != null) {
            sql.append(" and variables like ?");
            params.add(filter.variablesLike());
        }
    }

    private ProcessInstanceDetails mapDetailsRow(ResultSet rs) throws SQLException {
        var id = rs.getObject("id", UUID.class);
        var typeName = rs.getString("type");
        var type = processDefinitionRegistry.declaredType(typeName);
        var state = processDefinitionRegistry.declaredState(typeName, rs.getString("state"));
        var stateHistory = database.queryForList(SELECT_STATE_HIST_SQL,
                row -> mapStateHistoryRow(typeName, row), id);
        return new ProcessInstanceDetails(
                id,
                type,
                state,
                ProcessStatus.valueOf(rs.getString("status")),
                DateTimeUtils.toInstant(rs.getTimestamp("created_at")),
                Optional.ofNullable(DateTimeUtils.toInstant(rs.getTimestamp("next_run_at"))),
                Optional.ofNullable(rs.getString("business_key")),
                rs.getString("correlation_id"),
                rs.getInt("attempt"),
                Optional.ofNullable(rs.getString("last_error")),
                stateHistory,
                new ProcessVariablesSnapshot(ProcessVariables.fromString(rs.getString("variables"))),
                ProcessTimers.fromString(rs.getString("timers")).asMap()
        );
    }

    private StateHistoryEntry mapStateHistoryRow(String typeName, ResultSet rs) throws SQLException {
        return new StateHistoryEntry(
                processDefinitionRegistry.declaredState(typeName, rs.getString("state")),
                rs.getTimestamp("changed_at").toInstant()
        );
    }

    private ProcessInstanceSummary mapSummaryRow(ResultSet rs) throws SQLException {
        var typeName = rs.getString("type");
        var type = processDefinitionRegistry.declaredType(typeName);
        var state = processDefinitionRegistry.declaredState(typeName, rs.getString("state"));
        return new ProcessInstanceSummary(
                rs.getObject("id", UUID.class),
                type,
                state,
                ProcessStatus.valueOf(rs.getString("status")),
                DateTimeUtils.toInstant(rs.getTimestamp("created_at")),
                Optional.ofNullable(DateTimeUtils.toInstant(rs.getTimestamp("next_run_at"))),
                Optional.ofNullable(rs.getString("business_key")),
                rs.getInt("attempt"));
    }
}
