package io.github.neherim.ruslo.examples.cookbook.deadline;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class ReleaseSeatStep implements ProcessStep {
    private final SeatInventory seatInventory;

    public ReleaseSeatStep(SeatInventory seatInventory) {
        this.seatInventory = seatInventory;
    }

    /** Releases the seat when the payment did not arrive in time. */
    @Override
    public void execute(ProcessContext context) {
        seatInventory.release(context.getVariable(SeatReservationDefinition.SEAT));
    }
}
