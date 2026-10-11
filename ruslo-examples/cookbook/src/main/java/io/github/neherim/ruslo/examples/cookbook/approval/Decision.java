package io.github.neherim.ruslo.examples.cookbook.approval;

/**
 * Approver's decision.
 *
 * @param approver who made the decision
 * @param approved {@code true} if approved, {@code false} if rejected
 */
public record Decision(String approver, boolean approved, String comment) {
}
