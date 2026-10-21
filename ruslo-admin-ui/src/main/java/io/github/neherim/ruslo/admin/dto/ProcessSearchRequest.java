package io.github.neherim.ruslo.admin.dto;

import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Process search form. {@code datetime-local} values are read in the browser time zone {@code timeZone},
 * or in the server zone if it is absent.
 */
public record ProcessSearchRequest(
        UUID processId,
        String typeName,
        String stateName,
        ProcessStatus status,
        String businessKey,
        String correlationId,
        String nextRunAtFrom,
        String nextRunAtTo,
        String createdAtFrom,
        String createdAtTo,
        String variablesLike,
        Integer limit,
        Integer offset,
        String timeZone
) {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    /**
     * Adds the filled-in form conditions to {@code query} and returns it.
     */
    public ProcessQuery applyTo(ProcessQuery query) {
        if (processId != null) {
            query.byId(processId);
        }
        if (hasText(typeName)) {
            query.byTypeName(typeName);
        }
        if (hasText(stateName)) {
            query.byStateName(stateName);
        }
        if (status != null) {
            query.byStatus(status);
        }
        if (hasText(businessKey)) {
            query.byBusinessKey(businessKey);
        }
        if (hasText(correlationId)) {
            query.byCorrelationId(correlationId);
        }
        if (hasText(nextRunAtFrom)) {
            query.byNextRunAtFrom(parseHtmlDateTime(nextRunAtFrom, zone()));
        }
        if (hasText(nextRunAtTo)) {
            query.byNextRunAtTo(parseHtmlDateTime(nextRunAtTo, zone()));
        }
        if (hasText(createdAtFrom)) {
            query.byCreatedAtFrom(parseHtmlDateTime(createdAtFrom, zone()));
        }
        if (hasText(createdAtTo)) {
            query.byCreatedAtTo(parseHtmlDateTime(createdAtTo, zone()));
        }
        if (hasText(variablesLike)) {
            query.byVariablesLike(variablesLike);
        }
        return query;
    }

    /**
     * Returns the page size clamped to 1..100, or 50 if absent.
     */
    public int pageLimit() {
        return limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
    }

    /**
     * Returns the page offset, at least 0.
     */
    public int pageOffset() {
        return offset == null ? 0 : Math.max(0, offset);
    }

    private ZoneId zone() {
        return hasText(timeZone) ? ZoneId.of(timeZone) : ZoneId.systemDefault();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant parseHtmlDateTime(String value, ZoneId zone) {
        if (value.endsWith("Z")) {
            return Instant.parse(value);
        }
        return LocalDateTime.parse(value).atZone(zone).toInstant();
    }
}
