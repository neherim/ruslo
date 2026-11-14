package io.github.neherim.ruslo.admin.dto;

import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Подтверждаемая выборка со всех страниц и условия её подготовки. */
public record BulkProcessSnapshot(ProcessSearchRequest filter, String preparedAt, List<Selection> processes) {
    public BulkProcessSnapshot {
        processes = List.copyOf(processes);
    }

    /** Возвращает первые двадцать процессов для предварительного просмотра. */
    public List<Selection> preview() {
        return processes.subList(0, Math.min(20, processes.size()));
    }

    /** Возвращает количество выбранных процессов по типу, состоянию и статусу. */
    public Map<String, Long> groups() {
        var groups = new LinkedHashMap<String, Long>();
        processes.forEach(process -> groups.merge(
                process.type() + " / " + process.state() + " / " + process.status(), 1L, Long::sum));
        return groups;
    }

    /** Идентификатор процесса и ожидаемые значения на момент подготовки. */
    public record Selection(UUID id, String type, String state, ProcessStatus status) {
        /** Сохраняет проверяемые значения результата поиска. */
        public static Selection of(ProcessInstanceSummary process) {
            return new Selection(process.id(), process.type().name(), process.state().name(), process.status());
        }

        /** Проверяет, сохранились ли тип, состояние и статус процесса. */
        public boolean matches(ProcessInstanceSummary process) {
            return type.equals(process.type().name()) && state.equals(process.state().name())
                    && status == process.status();
        }
    }
}
