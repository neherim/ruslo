package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessType;

/**
 * Incomplete processes of one type; {@code type} is the declared object, see {@link ProcessType}.
 *
 * @param overdue {@code ACTIVE} processes with {@code nextRunAt} at or before the given moment; included in
 *                {@code active}
 */
public record ProcessTypeCounts(ProcessType type, long active, long failed, long overdue) {
}
