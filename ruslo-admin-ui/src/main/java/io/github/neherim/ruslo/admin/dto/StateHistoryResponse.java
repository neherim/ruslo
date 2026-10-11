package io.github.neherim.ruslo.admin.dto;

import java.time.Instant;

/**
 * Process state history entry.
 *
 * @param manual whether the transition is not declared in the definition, i.e. was made manually
 */
public record StateHistoryResponse(String state, Instant changedAt, boolean manual) {
}
