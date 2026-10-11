package io.github.neherim.ruslo.examples.cookbook.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Warehouse service stub. The reservation is keyed by order id, so both operations are idempotent. */
@Component
public class Warehouse {
    private static final Logger log = LoggerFactory.getLogger(Warehouse.class);

    /** Reserves the item for the order; a repeat for the same order changes nothing. */
    public void reserve(String orderId, String item) {
        log.info("Item {} reserved for order {}", item, orderId);
    }

    /** Releases the order reservation; does nothing without a reservation. */
    public void release(String orderId) {
        log.info("Reservation of order {} released", orderId);
    }
}
