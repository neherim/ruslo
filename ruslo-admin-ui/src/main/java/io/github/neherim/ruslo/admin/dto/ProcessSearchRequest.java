package io.github.neherim.ruslo.admin.dto;

import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
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
            query.byNextRunAtFrom(HtmlDateTime.parse(nextRunAtFrom, zone()));
        }
        if (hasText(nextRunAtTo)) {
            query.byNextRunAtTo(HtmlDateTime.parse(nextRunAtTo, zone()));
        }
        if (hasText(createdAtFrom)) {
            query.byCreatedAtFrom(HtmlDateTime.parse(createdAtFrom, zone()));
        }
        if (hasText(createdAtTo)) {
            query.byCreatedAtTo(HtmlDateTime.parse(createdAtTo, zone()));
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

    /** Фиксирует временные границы в UTC и исключает параметры пагинации. */
    public ProcessSearchRequest forBulk() {
        return new ProcessSearchRequest(processId, typeName, stateName, status, businessKey, correlationId,
                utc(nextRunAtFrom), utc(nextRunAtTo), utc(createdAtFrom), utc(createdAtTo), variablesLike,
                null, null, "UTC");
    }

    /** Возвращает заполненные условия для подтверждения выборки и журнала. */
    public Map<String, String> activeFilters() {
        var filters = new LinkedHashMap<String, String>();
        if (processId != null) filters.put("ID", processId.toString());
        if (hasText(typeName)) filters.put("Type", typeName);
        if (hasText(stateName)) filters.put("State", stateName);
        if (status != null) filters.put("Status", status.name());
        if (hasText(businessKey)) filters.put("Business key", businessKey);
        if (hasText(correlationId)) filters.put("Correlation ID", correlationId);
        if (hasText(nextRunAtFrom)) filters.put("Next run from (UTC)", nextRunAtFrom);
        if (hasText(nextRunAtTo)) filters.put("Next run to (UTC)", nextRunAtTo);
        if (hasText(createdAtFrom)) filters.put("Created from (UTC)", createdAtFrom);
        if (hasText(createdAtTo)) filters.put("Created to (UTC)", createdAtTo);
        if (hasText(variablesLike)) filters.put("Variables LIKE", variablesLike);
        return filters;
    }

    private String utc(String value) {
        return hasText(value) ? HtmlDateTime.parse(value, zone()).toString() : null;
    }

    private ZoneId zone() {
        return HtmlDateTime.zone(timeZone);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
