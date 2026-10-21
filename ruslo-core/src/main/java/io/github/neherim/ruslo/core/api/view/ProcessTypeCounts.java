package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;

/**
 * Incomplete processes of one type.
 *
 * @param overdue {@code ACTIVE} processes with {@code nextRunAt} at or before the given moment; included in
 *                {@code active}
 */
public record ProcessTypeCounts(ProcessTypeId type, long active, long failed, long overdue) {
}
