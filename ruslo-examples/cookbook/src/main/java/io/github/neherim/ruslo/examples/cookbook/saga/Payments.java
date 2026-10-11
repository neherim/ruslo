package io.github.neherim.ruslo.examples.cookbook.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Payment service stub. The payment is keyed by order id, so both operations are idempotent. */
@Component
public class Payments {
    private static final Logger log = LoggerFactory.getLogger(Payments.class);
    private static final BigDecimal LIMIT = new BigDecimal("1000");

    /**
     * Charges the order payment; a repeat for the same order returns the result of the first charge.
     *
     * @return {@code false} if the bank declined the payment
     */
    public boolean charge(String orderId, BigDecimal amount) {
        var approved = amount.compareTo(LIMIT) <= 0;
        log.info("Order {} charged {}: approved={}", orderId, amount, approved);
        return approved;
    }

    /** Refunds the order payment; does nothing without a payment. */
    public void refund(String orderId) {
        log.info("Payment of order {} refunded", orderId);
    }
}
