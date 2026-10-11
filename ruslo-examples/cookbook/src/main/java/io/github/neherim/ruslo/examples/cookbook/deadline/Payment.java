package io.github.neherim.ruslo.examples.cookbook.deadline;

import java.math.BigDecimal;

/**
 * Reservation payment.
 *
 * @param paymentId payment id in the payment system
 */
public record Payment(String paymentId, BigDecimal amount) {
}
