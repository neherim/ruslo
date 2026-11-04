package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.ProcessState;

import java.time.Instant;

/** A pending history entry: the process entered {@code state} at {@code changedAt}. */
public record ProcessStateHist(ProcessState state, Instant changedAt) {
}
