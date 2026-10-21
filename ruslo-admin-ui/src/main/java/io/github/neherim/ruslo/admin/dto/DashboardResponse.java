package io.github.neherim.ruslo.admin.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard summary. A process is overdue if it is {@code ACTIVE} and its next run is no later than {@code overdueAt};
 * {@code overdueAt} is a {@code datetime-local} value in {@code timeZone}, as in the process list filter.
 */
public record DashboardResponse(
        long activeCount,
        long failedCount,
        long overdueCount,
        String overdueAt,
        String timeZone,
        Map<String, TypeCounts> typeCounts,
        List<InstanceResponse> instances
) {
    private static final TypeCounts NO_PROCESSES = new TypeCounts(0, 0, 0);

    /**
     * Returns the counters of unfinished processes of {@code type}; zeros if there are none.
     */
    public TypeCounts countsFor(String type) {
        return typeCounts.getOrDefault(type, NO_PROCESSES);
    }

    /** Counts of unfinished processes of one type. */
    public record TypeCounts(long active, long failed, long overdue) {
    }

    /**
     * Registered engine instance; {@code expiresInSeconds} is negative if the registration has expired.
     */
    public record InstanceResponse(UUID id, long leaseCount, long expiresInSeconds, boolean current) {
    }
}
