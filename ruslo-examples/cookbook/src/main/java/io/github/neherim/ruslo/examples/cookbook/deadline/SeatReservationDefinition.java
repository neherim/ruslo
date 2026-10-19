package io.github.neherim.ruslo.examples.cookbook.deadline;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recipe: wait for an event for a limited time.
 * <p>
 * Task: reserve a seat for an event and wait 15 minutes for payment. If the payment does not arrive, release the
 * seat so another customer can take it.
 * <p>
 * How it works:
 * <ul>
 *   <li>Await {@link State#WAITING_FOR_PAYMENT} declares two events: message {@link #PAYMENT_RECEIVED} and
 *       {@code timeout(PAYMENT_DEADLINE, ...)}. Whichever comes first moves the process on.</li>
 *   <li>The deadline counts from entering the await, by the engine clock. The step does not need to read and
 *       compare the time itself.</li>
 *   <li>On the deadline the process goes to step {@link State#RELEASING}, which releases the seat. A separate step
 *       gets its own retries on error; a short side effect could also run in a {@code timeout(delay, handler, next)}
 *       handler.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>After the deadline the process does not accept the message: {@code sendMessage} throws
 *       {@code MessageNotExpectedException}. The sender handles a late payment, for example by refunding it. Which
 *       event wins when a message and the deadline are close is described in {@code docs/execution.md}.</li>
 *   <li>An event targeting the await itself does not reset the deadline. How to restart the wait with a new
 *       deadline is shown in recipe {@code approval}.</li>
 * </ul>
 */
@Component
public class SeatReservationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SEAT_RESERVATION");
    public static final Duration PAYMENT_DEADLINE = Duration.ofMinutes(15);
    public static final VariableType<String> SEAT = VariableType.of("seat", String.class);
    public static final VariableType<String> PAYMENT_ID = VariableType.of("paymentId", String.class);
    /** Payment of the reservation; the payload is the payment id in the payment system. */
    public static final MessageType<String> PAYMENT_RECEIVED = MessageType.of("paymentReceived", String.class);

    private static final Logger log = LoggerFactory.getLogger(SeatReservationDefinition.class);

    /** Describes the reservation: a payment confirms it, the deadline releases it. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.RESERVING)
            .step(State.RESERVING, SeatReservationDefinition::reserveSeat, State.WAITING_FOR_PAYMENT)
            .await(State.WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_RECEIVED, SeatReservationDefinition::onPayment, State.CONFIRMED)
                .timeout(PAYMENT_DEADLINE, State.RELEASING))
            .step(State.RELEASING, SeatReservationDefinition::releaseSeat, State.EXPIRED)
            .terminal(State.CONFIRMED)
            .terminal(State.EXPIRED)
            .build();
    }

    private static void reserveSeat(ProcessContext context) {
        log.info("Seat {} reserved", context.getVariable(SEAT));
    }

    private static void releaseSeat(ProcessContext context) {
        log.info("Seat {} released", context.getVariable(SEAT));
    }

    private static void onPayment(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(PAYMENT_ID, message.payload());
    }

    public enum State implements ProcessState {
        RESERVING,
        WAITING_FOR_PAYMENT,
        RELEASING,
        CONFIRMED,
        EXPIRED
    }
}
