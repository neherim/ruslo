package io.github.neherim.ruslo.examples.cookbook.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Delivery service stub. The delivery is keyed by order id, so both operations are idempotent. */
@Component
public class Delivery {
    private static final Logger log = LoggerFactory.getLogger(Delivery.class);

    /** Schedules the order delivery; a repeat for the same order changes nothing. */
    public void schedule(String orderId, String address) {
        if (address.contains("Antarctica")) {
            throw new IllegalStateException("No courier for " + address);
        }
        log.info("Delivery of order {} scheduled to {}", orderId, address);
    }

    /** Cancels the order delivery; does nothing without a delivery. */
    public void cancel(String orderId) {
        log.info("Delivery of order {} cancelled", orderId);
    }
}
