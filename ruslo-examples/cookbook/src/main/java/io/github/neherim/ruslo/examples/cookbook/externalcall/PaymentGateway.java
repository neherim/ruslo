package io.github.neherim.ruslo.examples.cookbook.externalcall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment provider stub that supports an idempotency key, like Stripe or Adyen: a repeated request with the same key
 * returns the result of the first one and does not charge again. A real application uses an HTTP client here that
 * passes the key in the {@code Idempotency-Key} header.
 */
@Component
public class PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);
    private static final BigDecimal CARD_LIMIT = new BigDecimal("1000");

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();

    /**
     * Charges the card. A repeated call with the same {@code idempotencyKey} returns the first charge.
     *
     * @param idempotencyKey key shared by all retries of one charge
     */
    public Charge charge(String idempotencyKey, String cardToken, BigDecimal amount) {
        return charges.computeIfAbsent(idempotencyKey, key -> {
            var approved = amount.compareTo(CARD_LIMIT) <= 0;
            log.info("Card {} charged {}: approved={}", cardToken, amount, approved);
            return new Charge("ch-" + UUID.randomUUID(), approved);
        });
    }
}
