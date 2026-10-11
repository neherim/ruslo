package io.github.neherim.ruslo.examples.cookbook.deadline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Seat inventory stub. A real application uses a seat table or an external booking service here. */
@Component
public class SeatInventory {
    private static final Logger log = LoggerFactory.getLogger(SeatInventory.class);

    /** Reserves the seat. */
    public void reserve(String seat) {
        log.info("Seat {} reserved", seat);
    }

    /** Releases the seat. */
    public void release(String seat) {
        log.info("Seat {} released", seat);
    }
}
