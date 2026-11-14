package io.github.neherim.ruslo.admin.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Reports committed progress, including when a bulk action stops early. */
public record BulkProcessResult(
        int selectedCount,
        int succeededCount,
        List<Skipped> skipped,
        @Nullable UUID interruptedProcessId
) {
    public BulkProcessResult {
        skipped = List.copyOf(skipped);
    }

    /** Returns the number of processes the operation did not reach. */
    public int notAttemptedCount() {
        return selectedCount - succeededCount - skipped.size() - (interruptedProcessId == null ? 0 : 1);
    }

    /** Returns a summary for the report and application log. */
    public String summary() {
        return succeededCount + " succeeded, " + skipped.size() + " skipped, " + notAttemptedCount() + " not attempted"
                + (interruptedProcessId == null ? "." : ". Operation stopped; check the interrupted process.");
    }

    /** Identifies a skipped process and gives the reason it was skipped. */
    public record Skipped(UUID processId, String reason, String message) {
    }

}
