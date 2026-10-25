package io.github.neherim.ruslo.examples.cookbook.deadline;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.TimerType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Recipe: wait for an event until a deadline that can move.
 * <p>
 * Task: reserve a seat for an event and hold it for 15 minutes until payment. Support can extend the deadline. If the
 * payment does not arrive in time, release the seat so another customer can take it.
 * <p>
 * How it works:
 * <ul>
 *   <li>Step {@link State#RESERVING} sets process timer {@link #PAYMENT_DUE} to the deadline: {@code context.now()}
 *       plus {@link #PAYMENT_TIME}. {@code context.now()} is the engine clock, so in tests the emulator moves it.</li>
 *   <li>Await {@link State#WAITING_FOR_PAYMENT} declares message {@link #PAYMENT_RECEIVED} and
 *       {@code at(PAYMENT_DUE, ...)}. Whichever comes first moves the process on. The engine compares the deadline
 *       with its clock: the step does not read and compare the time itself.</li>
 *   <li>Message {@link #EXTEND_PAYMENT_DUE} sets the timer to a new deadline and keeps waiting: {@code setTimer}
 *       moves it earlier or later without entering the await again.</li>
 *   <li>On the deadline the process goes to step {@link State#RELEASING}, which releases the seat. A separate step
 *       gets its own retries on error; a short side effect could also run in an {@code at(timer, handler, next)}
 *       handler.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>After the deadline the process does not accept the message: {@code sendMessage} throws
 *       {@code MessageNotExpectedException}. The sender handles a late payment, for example by refunding it. Which
 *       event wins when a message and the deadline are close is described in {@code docs/execution.md}.</li>
 *   <li>Take the time from {@code context.now()}, not {@code Instant.now()}: a test cannot move the system clock.</li>
 *   <li>The step sets the timer; without it the reservation waits for the payment forever. A fixed delay that never
 *       moves is simpler as {@code after(delay, next)}.</li>
 * </ul>
 */
@Component
public class SeatReservationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SEAT_RESERVATION");
    public static final Duration PAYMENT_TIME = Duration.ofMinutes(15);
    public static final VariableType<String> SEAT = VariableType.of("seat", String.class);
    public static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");
    public static final VariableType<String> PAYMENT_ID = VariableType.of("paymentId", String.class);
    /** Payment of the reservation; the payload is the payment id in the payment system. */
    public static final MessageType<String> PAYMENT_RECEIVED = MessageType.of("paymentReceived", String.class);
    /** New payment deadline. */
    public static final MessageType<Instant> EXTEND_PAYMENT_DUE = MessageType.of("extendPaymentDue", Instant.class);

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
                .message(EXTEND_PAYMENT_DUE, SeatReservationDefinition::onExtend, State.WAITING_FOR_PAYMENT)
                .at(PAYMENT_DUE, State.RELEASING))
            .step(State.RELEASING, SeatReservationDefinition::releaseSeat, State.EXPIRED)
            .terminal(State.CONFIRMED)
            .terminal(State.EXPIRED)
            .build();
    }

    private static void reserveSeat(ProcessContext context) {
        var due = context.now().plus(PAYMENT_TIME);
        context.setTimer(PAYMENT_DUE, due);
        log.info("Seat {} reserved until {}", context.getVariable(SEAT), due);
    }

    private static void releaseSeat(ProcessContext context) {
        log.info("Seat {} released", context.getVariable(SEAT));
    }

    private static void onPayment(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(PAYMENT_ID, message.payload());
    }

    private static void onExtend(ProcessContext context, IncomingMessage<Instant> message) {
        context.setTimer(PAYMENT_DUE, message.payload());
    }

    public enum State implements ProcessState {
        RESERVING,
        WAITING_FOR_PAYMENT,
        RELEASING,
        CONFIRMED,
        EXPIRED
    }
}
