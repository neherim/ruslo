package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.exception.TooManyRowsException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Process search. Filters are combined with AND. Created by {@link ProcessEngine#processes()}.
 */
public interface ProcessQuery {

    /** Locks the found rows with {@code SELECT ... FOR UPDATE} until the caller's transaction ends. */
    ProcessQuery withLock();

    ProcessQuery byId(UUID processId);

    ProcessQuery byType(ProcessType type);

    ProcessQuery byTypeName(String typeName);

    ProcessQuery byState(ProcessState state);

    ProcessQuery byStateName(String stateName);

    ProcessQuery byStatus(ProcessStatus status);

    /** Keeps processes with at least one failed attempt in the current state. */
    ProcessQuery byAttempted();

    ProcessQuery byBusinessKey(String businessKey);

    ProcessQuery byCorrelationId(String correlationId);

    /** Keeps processes with {@code nextRunAt >= nextRunAtFrom}. */
    ProcessQuery byNextRunAtFrom(Instant nextRunAtFrom);

    /** Keeps processes with {@code nextRunAt <= nextRunAtTo}. */
    ProcessQuery byNextRunAtTo(Instant nextRunAtTo);

    /** Keeps processes with {@code createdAt >= createdAtFrom}. */
    ProcessQuery byCreatedAtFrom(Instant createdAtFrom);

    /** Keeps processes with {@code createdAt <= createdAtTo}. */
    ProcessQuery byCreatedAtTo(Instant createdAtTo);

    /**
     * Matches variables with SQL {@code LIKE}. Variables are stored as one JSON object without spaces, e.g.
     * {@code {"key":"value","amount":42}}, so {@code "%\"key\":\"value\"%"} finds {@code key = "value"}.
     */
    ProcessQuery byVariablesLike(String pattern);

    List<ProcessInstanceSummary> list(int limit, int offset);

    List<ProcessInstanceSummary> list();

    /** @throws TooManyRowsException if more than one process matches */
    Optional<ProcessInstanceSummary> single();

    /** @throws TooManyRowsException if more than one process matches */
    Optional<ProcessInstanceDetails> singleDetails();
}
