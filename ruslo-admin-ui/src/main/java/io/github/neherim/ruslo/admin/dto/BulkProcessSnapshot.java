package io.github.neherim.ruslo.admin.dto;

import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captures matching processes across all pages and the filter used to select them. */
public record BulkProcessSnapshot(ProcessSearchRequest filter, String preparedAt, List<Selection> processes) {
    public BulkProcessSnapshot {
        processes = List.copyOf(processes);
    }

    /** Returns up to twenty processes for the preview. */
    public List<Selection> preview() {
        return processes.subList(0, Math.min(20, processes.size()));
    }

    /** Returns the number of selected processes by type, state, and status. */
    public Map<String, Long> groups() {
        var groups = new LinkedHashMap<String, Long>();
        processes.forEach(process -> groups.merge(
                process.type() + " / " + process.state() + " / " + process.status(), 1L, Long::sum));
        return groups;
    }

    /** Captures a process ID and its type, state, and status at preview time. */
    public record Selection(UUID id, String type, String state, ProcessStatus status) {
        /** Captures the fields used to recheck a process before execution. */
        public static Selection of(ProcessInstanceSummary process) {
            return new Selection(process.id(), process.type().name(), process.state().name(), process.status());
        }

        /** Checks whether the process still has the same type, state, and status. */
        public boolean matches(ProcessInstanceSummary process) {
            return type.equals(process.type().name()) && state.equals(process.state().name())
                    && status == process.status();
        }
    }
}
