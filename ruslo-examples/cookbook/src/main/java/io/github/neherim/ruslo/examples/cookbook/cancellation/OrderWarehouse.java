package io.github.neherim.ruslo.examples.cookbook.cancellation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Warehouse stub. The reservation is keyed by order id, so the operations are idempotent. */
@Component
public class OrderWarehouse {
    private static final Logger log = LoggerFactory.getLogger(OrderWarehouse.class);

    /** Reserves the order items. */
    public void reserve(String orderId) {
        log.info("Items of order {} reserved", orderId);
    }

    /** Packs the order for the courier. */
    public void pack(String orderId) {
        log.info("Order {} packed", orderId);
    }

    /** Returns the order items to the shelf; does nothing without a reservation. */
    public void release(String orderId) {
        log.info("Items of order {} released", orderId);
    }
}
