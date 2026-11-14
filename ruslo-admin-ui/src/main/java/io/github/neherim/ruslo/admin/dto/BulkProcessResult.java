package io.github.neherim.ruslo.admin.dto;

import java.util.List;
import java.util.UUID;

/** Результат выполнения с сохранением уже зафиксированного прогресса при остановке. */
public record BulkProcessResult(
        int selectedCount,
        int succeededCount,
        List<Skipped> skipped,
        Interruption interruption,
        int notAttemptedCount
) {
    public BulkProcessResult {
        skipped = List.copyOf(skipped);
    }

    /** Возвращает краткий итог для отчёта и журнала. */
    public String summary() {
        return succeededCount + " succeeded, " + skipped.size() + " skipped, " + notAttemptedCount + " not attempted"
                + (interruption == null ? "." : ". Operation stopped; check the interrupted process.");
    }

    /** Пропущенный процесс с диагностикой. */
    public record Skipped(UUID processId, String reason, String message) {
    }

    /** Процесс, исход изменения которого не подтверждён из-за неожиданной ошибки. */
    public record Interruption(UUID processId) {
    }
}
