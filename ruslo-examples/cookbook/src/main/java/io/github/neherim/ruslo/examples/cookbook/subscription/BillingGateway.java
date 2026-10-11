package io.github.neherim.ruslo.examples.cookbook.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment provider stub with an idempotency key: a repeat with the same key returns the result of the first request,
 * including a decline. Card {@code tok_declined} is always declined.
 */
@Component
public class BillingGateway {
    public static final String DECLINED_CARD = "tok_declined";
    private static final Logger log = LoggerFactory.getLogger(BillingGateway.class);

    private final Map<String, Boolean> charges = new ConcurrentHashMap<>();

    /**
     * Charges the card.
     *
     * @return {@code false} if the bank declined the charge
     */
    public boolean charge(String idempotencyKey, String cardToken, BigDecimal amount) {
        return charges.computeIfAbsent(idempotencyKey, key -> {
            var approved = !DECLINED_CARD.equals(cardToken);
            log.info("Subscription charge {} of {}: approved={}", key, amount, approved);
            return approved;
        });
    }
}
