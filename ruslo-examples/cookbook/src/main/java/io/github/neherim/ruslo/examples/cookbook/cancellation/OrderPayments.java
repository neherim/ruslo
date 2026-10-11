package io.github.neherim.ruslo.examples.cookbook.cancellation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Payment service stub. The payment is keyed by order id, so a refund is idempotent. */
@Component
public class OrderPayments {
    private static final Logger log = LoggerFactory.getLogger(OrderPayments.class);

    /** Refunds the order payment; does nothing if the order is not paid. */
    public void refund(String orderId) {
        log.info("Payment of order {} refunded", orderId);
    }
}
