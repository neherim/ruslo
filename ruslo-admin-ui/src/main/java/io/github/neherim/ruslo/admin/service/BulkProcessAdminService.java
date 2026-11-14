package io.github.neherim.ruslo.admin.service;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.dto.BulkProcessResult.*;
import io.github.neherim.ruslo.admin.dto.BulkProcessSnapshot.Selection;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Массовые действия вне бизнес-транзакции; Database должен быть тем же экземпляром, который использует движок.
 * Проверка и изменение одного процесса выполняются в общей транзакции, разные процессы фиксируются независимо.
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

    /** Возвращает причину недоступности действия либо пустую строку. */
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

    /** Фиксирует все совпадения с нормализованным фильтром, без ограничения количества. */
    public BulkProcessSnapshot prepare(BulkProcessOperation operation, ProcessSearchRequest filter) {
        var normalized = filter.forBulk();
        requireApplicable(operation, normalized);
        var processes = normalized.applyTo(engine.processes()).list().stream().map(Selection::of).toList();
        return new BulkProcessSnapshot(normalized, Instant.now().toString(), processes);
    }

    /** Возобновляет подтверждённые FAILED-процессы и возвращает частичный результат при остановке. */
    public BulkProcessResult retry(BulkProcessSnapshot snapshot) {
        validate(snapshot, BulkProcessOperation.RETRY);
        return execute(BulkProcessOperation.RETRY, snapshot, null);
    }

    /** Переводит подтверждённые процессы одного типа в состояние их определения. */
    public BulkProcessResult changeState(BulkProcessSnapshot snapshot, String targetState) {
        validate(snapshot, BulkProcessOperation.CHANGE_STATE);
        var definition = admin.listProcessSpecifications()
            .stream()
            .filter(spec -> spec.type().name().equals(snapshot.filter().typeName()))
            .findFirst()
            .orElseThrow();
        if (definition.states()
            .stream()
            .map(ProcessSpecInfo.StateInfo::state)
            .noneMatch(state -> state.name().equals(targetState))) {
            throw new IllegalArgumentException("Select a state declared by the selected process type");
        }
        return execute(BulkProcessOperation.CHANGE_STATE, snapshot, ProcessState.of(targetState));
    }

    private BulkProcessResult execute(
            BulkProcessOperation operation,
            BulkProcessSnapshot snapshot,
            ProcessState target
    ) {
        var filter = snapshot.filter().forBulk();
        var skipped = new ArrayList<Skipped>();
        int succeeded = 0;
        for (var selection : snapshot.processes()) {
            try {
                var rejection = database.doInTransactionWithResult(() -> apply(operation, filter, selection, target));
                if (rejection == null) succeeded++;
                else
                    skipped.add(rejection);
            } catch (ProcessStatusException ex) {
                skipped.add(new Skipped(selection.id(), "STATUS_NOT_APPLICABLE", ex.getMessage()));
            } catch (ProcessNotFoundException ex) {
                skipped.add(new Skipped(selection.id(), "PROCESS_MISSING", "Process no longer exists"));
            } catch (RuntimeException ex) {
                log.warn("Bulk {} stopped at process {}", operation, selection.id(), ex);
                return new BulkProcessResult(snapshot.processes().size(), succeeded, skipped,
                        new Interruption(selection.id()), snapshot.processes().size() - succeeded - skipped.size() - 1);
            }
        }
        return new BulkProcessResult(snapshot.processes().size(), succeeded, skipped, null, 0);
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
            if (!selection.matches(diagnostic.get()))
                return changed(selection, diagnostic.get().state().name(), diagnostic.get().status());
            return new Skipped(selection.id(), "FILTER_NO_LONGER_MATCHES",
                    "Process no longer matches the confirmed filter");
        }
        if (!selection.matches(current.get()))
            return changed(selection, current.get().state().name(), current.get().status());
        if (operation == BulkProcessOperation.RETRY) engine.resumeFailedProcess(selection.id());
        else
            admin.moveProcessToState(selection.id(), target);
        return null;
    }

    private static Skipped changed(Selection selection, String state, ProcessStatus status) {
        return new Skipped(selection.id(), "PROCESS_CHANGED",
                "Process changed since preview; now " + state + " / " + status);
    }

    private void validate(BulkProcessSnapshot snapshot, BulkProcessOperation operation) {
        Objects.requireNonNull(snapshot, "Snapshot is required");
        Objects.requireNonNull(snapshot.filter(), "Filter is required");
        snapshot.filter().forBulk();
        requireApplicable(operation, snapshot.filter());
        Instant.parse(snapshot.preparedAt());
        if (snapshot.processes().isEmpty()) throw new IllegalArgumentException("No processes selected");
        var ids = new HashSet<UUID>();
        for (var selection : snapshot.processes()) {
            if (selection.id() == null || selection.type() == null || selection.type().isBlank()
                    || selection.state() == null || selection.state().isBlank() || selection.status() == null) {
                throw new IllegalArgumentException("Invalid process selection");
            }
            if (!ids.add(selection.id())) throw new IllegalArgumentException("Duplicate process ID in selection");
            var filter = snapshot.filter();
            if ((filter.processId() != null && !filter.processId().equals(selection.id()))
                    || (hasText(filter.typeName()) && !filter.typeName().equals(selection.type()))
                    || (hasText(filter.stateName()) && !filter.stateName().equals(selection.state()))
                    || (filter.status() != null && filter.status() != selection.status())) {
                throw new IllegalArgumentException("Selection does not match the confirmed filter");
            }
        }
    }

    private void requireApplicable(BulkProcessOperation operation, ProcessSearchRequest filter) {
        var reason = unavailableReason(operation, filter);
        if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
