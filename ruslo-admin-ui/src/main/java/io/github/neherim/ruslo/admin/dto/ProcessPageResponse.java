package io.github.neherim.ruslo.admin.dto;

import java.util.List;

/**
 * Page of process search results.
 */
public record ProcessPageResponse(
        List<ProcessSummaryResponse> items,
        boolean hasNext,
        int limit,
        int offset) {
}
