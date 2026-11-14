package io.github.neherim.ruslo.admin.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Результат выполнения с сохранением уже зафиксированного прогресса при остановке. */
public record BulkProcessResult(
        int selectedCount,
        int succeededCount,
        List<Skipped> skipped,
        @Nullable UUID interruptedProcessId
) {
    public BulkProcessResult {
        skipped = List.copyOf(skipped);
    }

    /** Возвращает число процессов, до которых выполнение не дошло. */
    public int notAttemptedCount() {
        return selectedCount - succeededCount - skipped.size() - (interruptedProcessId == null ? 0 : 1);
    }

    /** Возвращает краткий итог для отчёта и журнала. */
    public String summary() {
        return succeededCount + " succeeded, " + skipped.size() + " skipped, " + notAttemptedCount() + " not attempted"
                + (interruptedProcessId == null ? "." : ". Operation stopped; check the interrupted process.");
    }

    /** Пропущенный процесс с диагностикой. */
    public record Skipped(UUID processId, String reason, String message) {
    }

}
