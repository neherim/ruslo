package io.github.neherim.ruslo.examples.cookbook.branching;

/**
 * Employee's decision on the application.
 *
 * @param reviewer the employee who decided
 * @param comment  comment; for a rejection, its reason
 */
public record ReviewDecision(String reviewer, boolean approved, String comment) {
}
