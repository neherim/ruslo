package io.github.neherim.ruslo.admin.dto;

import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Instant;
import java.util.UUID;

/** Process row of the process list. */
public record ProcessSummaryResponse(
        UUID id,
        String type,
        String state,
        ProcessStatus status,
        Instant createdAt,
        Instant scheduledAt,
        String businessKey
) {
}
