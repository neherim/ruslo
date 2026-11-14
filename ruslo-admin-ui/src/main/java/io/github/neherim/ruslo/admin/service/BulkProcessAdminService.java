package io.github.neherim.ruslo.admin.service;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.dto.BulkProcessResult.Skipped;
import io.github.neherim.ruslo.admin.dto.BulkProcessSnapshot.Selection;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs bulk actions outside application transactions using the same {@link Database} instance as the engine.
 * Each process is checked and changed in its own transaction.
 */
public class BulkProcessAdminService {
    private static final Logger log = LoggerFactory.getLogger(BulkProcessAdminService.class);
    private final ProcessEngine engine;
    private final ProcessEngineAdmin admin;
    private final Database database;

    public BulkProcessAdminService(ProcessEngine engine, ProcessEngineAdmin admin, Database database) {
        this.engine = engine;
        this.admin = admin;
        this.database = database;
    }

    /** Returns the reason the action is unavailable, or an empty string if it is available. */
    public String unavailableReason(BulkProcessOperation operation, ProcessSearchRequest filter) {
        if (operation == BulkProcessOperation.RETRY) {
            return filter.status() == ProcessStatus.FAILED ? "" : "Filter by FAILED status";
        }
        if (filter.typeName() == null || filter.typeName().isBlank()) return "Select a single process type";
        if (admin.listProcessSpecifications()
            .stream()
            .noneMatch(spec -> spec.type().name().equals(filter.typeName()))) {
            return "Select a registered process type";
        }
        return filter.status() == ProcessStatus.COMPLETED ? "Completed processes cannot change state" : "";
    }

    /** Captures all processes matching the normalized filter across all pages. */
    public BulkProcessSnapshot prepare(BulkProcessOperation operation, ProcessSearchRequest filter) {
        var normalized = filter.forBulk();
        requireApplicable(operation, normalized);
        var processes = normalized.applyTo(engine.processes()).list().stream().map(Selection::of).toList();
        return new BulkProcessSnapshot(normalized, Instant.now().toString(), processes);
    }

    /** Retries the confirmed failed processes and reports partial progress if execution stops. */
    public BulkProcessResult retry(BulkProcessSnapshot snapshot) {
        var filter = validate(snapshot, BulkProcessOperation.RETRY);
        return execute(BulkProcessOperation.RETRY, filter, snapshot.processes(), null);
    }

    /** Moves the confirmed processes of one type to a state declared by their definition. */
    public BulkProcessResult changeState(BulkProcessSnapshot snapshot, String targetState) {
        var filter = validate(snapshot, BulkProcessOperation.CHANGE_STATE);
        var definition = admin.listProcessSpecifications()
            .stream()
            .filter(spec -> spec.type().name().equals(filter.typeName()))
            .findFirst()
            .orElseThrow();
        if (definition.states()
            .stream()
            .map(ProcessSpecInfo.StateInfo::state)
            .noneMatch(state -> state.name().equals(targetState))) {
            throw new IllegalArgumentException("Select a state declared by the selected process type");
        }
        return execute(BulkProcessOperation.CHANGE_STATE, filter, snapshot.processes(), ProcessState.of(targetState));
    }

    private BulkProcessResult execute(
            BulkProcessOperation operation,
            ProcessSearchRequest filter,
            List<Selection> processes,
            ProcessState target
    ) {
        var skipped = new ArrayList<Skipped>();
        int succeeded = 0;
        for (var selection : processes) {
            try {
                var rejection = database.doInTransactionWithResult(() -> apply(operation, filter, selection, target));
                if (rejection == null) succeeded++;
                else
                    skipped.add(rejection);
            } catch (RuntimeException ex) {
                log.warn("Bulk {} stopped at process {}", operation, selection.id(), ex);
                return new BulkProcessResult(processes.size(), succeeded, skipped, selection.id());
            }
        }
        return new BulkProcessResult(processes.size(), succeeded, skipped, null);
    }

    private Skipped apply(
            BulkProcessOperation operation,
            ProcessSearchRequest filter,
            Selection selection,
            ProcessState target
    ) {
        var current = filter.applyTo(engine.processes()).byId(selection.id()).withLock().single();
        if (current.isEmpty()) {
            var diagnostic = engine.processes().byId(selection.id()).single();
            if (diagnostic.isEmpty()) return new Skipped(selection.id(), "PROCESS_MISSING", "Process no longer exists");
            var process = diagnostic.get();
            if (!selection.matches(process)) return changed(selection, process.state().name(), process.status());
            return new Skipped(selection.id(), "FILTER_NO_LONGER_MATCHES",
                    "Process no longer matches the confirmed filter");
        }
        var process = current.get();
        if (!selection.matches(process)) return changed(selection, process.state().name(), process.status());
        if (process.status() == ProcessStatus.COMPLETED) {
            return new Skipped(selection.id(), "STATUS_NOT_APPLICABLE", "Completed processes cannot change state");
        }
        if (operation == BulkProcessOperation.RETRY) engine.resumeFailedProcess(selection.id());
        else
            admin.moveProcessToState(selection.id(), target);
        return null;
    }

    private static Skipped changed(Selection selection, String state, ProcessStatus status) {
        return new Skipped(selection.id(), "PROCESS_CHANGED",
                "Process changed since preview; now " + state + " / " + status);
    }

    private ProcessSearchRequest validate(BulkProcessSnapshot snapshot, BulkProcessOperation operation) {
        Objects.requireNonNull(snapshot, "Snapshot is required");
        var filter = Objects.requireNonNull(snapshot.filter(), "Filter is required").forBulk();
        requireApplicable(operation, filter);
        if (snapshot.processes().isEmpty()) throw new IllegalArgumentException("No processes selected");
        var ids = new HashSet<UUID>();
        for (var selection : snapshot.processes()) {
            if (selection.id() == null || selection.type() == null || selection.type().isBlank()
                    || selection.state() == null || selection.state().isBlank() || selection.status() == null) {
                throw new IllegalArgumentException("Invalid process selection");
            }
            if (!ids.add(selection.id())) throw new IllegalArgumentException("Duplicate process ID in selection");
            if ((filter.processId() != null && !filter.processId().equals(selection.id()))
                    || (hasText(filter.typeName()) && !filter.typeName().equals(selection.type()))
                    || (hasText(filter.stateName()) && !filter.stateName().equals(selection.state()))
                    || (filter.status() != null && filter.status() != selection.status())) {
                throw new IllegalArgumentException("Selection does not match the confirmed filter");
            }
        }
        return filter;
    }

    private void requireApplicable(BulkProcessOperation operation, ProcessSearchRequest filter) {
        var reason = unavailableReason(operation, filter);
        if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
