package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails.StateHistoryEntry;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.runtime.process.ProcessVariables;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.utils.DateTimeUtils;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/** Searches and filters process instances. */
public class ProcessInstanceSearchDao {
    private static final String SELECT_SUMMARIES_SQL = """
            select id, type, state, status, create_at, scheduled_at, business_key, attempt
            from xs_process_instance where 1=1
            """;

    private static final String SELECT_DETAILS_SQL = """
            select id, type, state, status, create_at, scheduled_at, business_key, attempt,
                   correlation_id, last_error, variables
            from xs_process_instance where 1=1
            """;

    private static final String SELECT_STATE_HIST_SQL = """
            select state, changed_at from xs_process_state_hist
            where link_process_instance = ?
            order by changed_at, id
            """;

    private final Database database;
    private final ObjectMapper objectMapper;

    public ProcessInstanceSearchDao(Database database, ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
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

        sql.append(" order by create_at desc");

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

    public List<ProcessInstanceDetails> findDetails(ProcessSearchCriteria criteria, boolean lock) {
        var sql = new StringBuilder(SELECT_DETAILS_SQL);
        var params = new ArrayList<>();
        appendFilterClauses(sql, params, criteria);

        if (lock) {
            sql.append(" for update");
        }

        return database.queryForList(sql.toString(), this::mapDetailsRow, params.toArray());
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
        if (filter.scheduledAtFrom() != null) {
            sql.append(" and scheduled_at >= ?");
            params.add(Timestamp.from(filter.scheduledAtFrom()));
        }
        if (filter.scheduledAtTo() != null) {
            sql.append(" and scheduled_at <= ?");
            params.add(Timestamp.from(filter.scheduledAtTo()));
        }
        if (filter.createdAtFrom() != null) {
            sql.append(" and create_at >= ?");
            params.add(Timestamp.from(filter.createdAtFrom()));
        }
        if (filter.createdAtTo() != null) {
            sql.append(" and create_at <= ?");
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
        var type = ProcessType.of(rs.getString("type"));
        var state = ProcessState.of(rs.getString("state"));
        var stateHistory = database.queryForList(SELECT_STATE_HIST_SQL, this::mapStateHistoryRow, id);
        return new ProcessInstanceDetails(
                id,
                type,
                state,
                ProcessStatus.valueOf(rs.getString("status")),
                DateTimeUtils.toInstant(rs.getTimestamp("create_at")),
                Optional.ofNullable(DateTimeUtils.toInstant(rs.getTimestamp("scheduled_at"))),
                Optional.ofNullable(rs.getString("business_key")),
                rs.getString("correlation_id"),
                rs.getInt("attempt"),
                Optional.ofNullable(rs.getString("last_error")),
                stateHistory,
                new ProcessVariablesSnapshot(ProcessVariables.fromString(objectMapper, rs.getString("variables")))
        );
    }

    private StateHistoryEntry mapStateHistoryRow(ResultSet rs) throws SQLException {
        return new StateHistoryEntry(
                ProcessState.of(rs.getString("state")),
                rs.getTimestamp("changed_at").toInstant()
        );
    }

    private ProcessInstanceSummary mapSummaryRow(ResultSet rs) throws SQLException {
        var type = ProcessType.of(rs.getString("type"));
        var state = ProcessState.of(rs.getString("state"));
        return new ProcessInstanceSummary(
                rs.getObject("id", UUID.class),
                type,
                state,
                ProcessStatus.valueOf(rs.getString("status")),
                DateTimeUtils.toInstant(rs.getTimestamp("create_at")),
                Optional.ofNullable(DateTimeUtils.toInstant(rs.getTimestamp("scheduled_at"))),
                Optional.ofNullable(rs.getString("business_key")),
                rs.getInt("attempt"));
    }
}
