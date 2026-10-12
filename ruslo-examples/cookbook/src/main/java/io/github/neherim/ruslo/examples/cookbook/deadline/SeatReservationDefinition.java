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
 *   <li>On the deadline the process goes to step {@link State#RELEASING}, which releases the seat. The side effect
 *       could also run in a {@code timeout(delay, handler, next)} handler; a separate step is handier when it needs
 *       its own retries on error or a different worker.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>After the deadline the process does not accept the message: {@code sendMessage} throws
 *       {@code MessageNotExpectedException}. The sender handles a late payment, for example by refunding it.</li>
 *   <li>A message accepted after the deadline, but before the dispatcher handled the process, is deleted on the
 *       {@code timeout} transition even though {@code sendMessage} succeeded. Event time decides the order, see
 *       {@code docs/execution.md}.</li>
 *   <li>An error in the payment handler does not extend the wait: if the retry is scheduled after the deadline,
 *       {@code timeout} fires.</li>
 *   <li>To restart the wait with a new deadline, go to a step that leads back to the await. A message targeting
 *       the await itself does not reset the deadline.</li>
 * </ul>
 */
@Component
public class SeatReservationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SEAT_RESERVATION");
    public static final Duration PAYMENT_DEADLINE = Duration.ofMinutes(15);
    public static final VariableType<String> SEAT = VariableType.of("seat", String.class);
    public static final VariableType<String> PAYMENT_ID = VariableType.of("paymentId", String.class);
    public static final MessageType<Payment> PAYMENT_RECEIVED = MessageType.of("paymentReceived", Payment.class);

    private final ReserveSeatStep reserveSeatStep;
    private final ReleaseSeatStep releaseSeatStep;

    public SeatReservationDefinition(ReserveSeatStep reserveSeatStep, ReleaseSeatStep releaseSeatStep) {
        this.reserveSeatStep = reserveSeatStep;
        this.releaseSeatStep = releaseSeatStep;
    }

    /** Describes the reservation: a payment confirms it, the deadline releases it. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.RESERVING)
            .step(State.RESERVING, reserveSeatStep, State.WAITING_FOR_PAYMENT)
            .await(State.WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_RECEIVED, SeatReservationDefinition::onPayment, State.CONFIRMED)
                .timeout(PAYMENT_DEADLINE, State.RELEASING))
            .step(State.RELEASING, releaseSeatStep, State.EXPIRED)
            .terminal(State.CONFIRMED)
            .terminal(State.EXPIRED)
            .build();
    }

    /** Stores the payment that confirmed the reservation. */
    private static void onPayment(ProcessContext context, IncomingMessage<Payment> message) {
        context.setVariable(PAYMENT_ID, message.payload().paymentId());
    }

    public enum State implements ProcessState {
        RESERVING,
        WAITING_FOR_PAYMENT,
        RELEASING,
        CONFIRMED,
        EXPIRED
    }
}
