package io.github.neherim.ruslo.examples.cookbook.externalcall;

/**
 * Result of a charge at the payment provider.
 *
 * @param chargeId charge id at the provider
 * @param approved {@code false} if the bank declined the charge
 */
public record Charge(String chargeId, boolean approved) {
}
