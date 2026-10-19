package io.github.neherim.ruslo.examples.cookbook.externalcall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Payment provider stub. A real provider (Stripe, Adyen) takes an idempotency key in the {@code Idempotency-Key}
 * header and answers a repeated request with the result of the first one instead of charging again.
 */
@Component
public class PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);
    private static final BigDecimal CARD_LIMIT = new BigDecimal("1000");

    /**
     * Charges the card.
     *
     * @param idempotencyKey key shared by all retries of one charge
     * @return {@code false} if the bank declined the charge
     */
    public boolean charge(String idempotencyKey, BigDecimal amount) {
        var approved = amount.compareTo(CARD_LIMIT) <= 0;
        log.info("Charge {} of {}: approved={}", idempotencyKey, amount, approved);
        return approved;
    }
}
