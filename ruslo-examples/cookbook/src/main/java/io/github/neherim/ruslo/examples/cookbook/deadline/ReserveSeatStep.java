package io.github.neherim.ruslo.examples.cookbook.deadline;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class ReserveSeatStep implements ProcessStep {
    private final SeatInventory seatInventory;

    public ReserveSeatStep(SeatInventory seatInventory) {
        this.seatInventory = seatInventory;
    }

    /** Reserves the seat; the process then waits for payment. */
    @Override
    public void execute(ProcessContext context) {
        seatInventory.reserve(context.getVariable(SeatReservationDefinition.SEAT));
    }
}
