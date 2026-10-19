package io.github.neherim.ruslo.examples.cookbook.branching;

/**
 * Employee's decision on the application.
 *
 * @param comment comment; for a rejection, its reason
 */
public record ReviewDecision(boolean approved, String comment) {
}
