package io.github.neherim.ruslo.examples.cookbook.cancellation;

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

/**
 * Recipe: cancel a long process at any stage on a user request.
 * <p>
 * Task: an order goes through item reservation, payment, packing and handover to a courier. Until the order is
 * handed to the courier, the customer may cancel it; then the reservation is released and the payment refunded.
 * <p>
 * How it works:
 * <ul>
 *   <li>Message {@link #CANCEL} is declared in every await where cancellation is allowed and leads to one step,
 *       {@link State#CANCELLING}. In {@link State#SHIPPED} the process has completed and can no longer be
 *       cancelled.</li>
 *   <li>Step {@link State#CANCELLING} is a compensation: it undoes every action by order id, and an undo does
 *       nothing if there is nothing to undo, so the step runs the same way whatever the stage of the cancellation.</li>
 *   <li>{@link OrderCancellationService} accepts the cancellation with {@code sendMessageSync}: the transition to
 *       {@link State#CANCELLING} is committed before the method returns, so the customer gets a definite answer
 *       at once.</li>
 *   <li>Only an await accepts messages. While the process runs a step, {@code sendMessageSync} throws
 *       {@code MessageNotExpectedException}, and {@link OrderCancellationService} answers {@code BUSY}. A step always moves
 *       on to an await or a terminal state, so a retry in a few seconds gets a final answer.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>{@code sendMessage} instead of {@code sendMessageSync} only queues the cancellation. If a payment was queued
 *       before it, the process moves to {@link State#PACKING}, the cancellation is deleted with the await queue, and
 *       the order ships although the customer was told "cancelled".</li>
 *   <li>{@code sendMessageSync} handles the cancellation out of queue order, and an already queued payment is
 *       deleted unhandled, and {@link #PAYMENT_ID} may be missing: undo by order id.</li>
 *   <li>A step retried after errors answers {@code BUSY} for the whole retry period: keep steps short. A process
 *       in {@code FAILED} is not handled synchronously, and {@link OrderCancellationService} answers {@code MANUAL};
 *       an operator cancels such an order in the admin UI: Change state → {@link State#CANCELLING}.</li>
 * </ul>
 */
@Component
public class OrderDeliveryDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER_DELIVERY");
    public static final VariableType<String> PAYMENT_ID = VariableType.of("paymentId", String.class);
    public static final VariableType<String> COURIER = VariableType.of("courier", String.class);
    public static final VariableType<String> CANCELLATION_REASON = VariableType.of("cancellationReason", String.class);
    public static final MessageType<String> PAYMENT_RECEIVED = MessageType.of("paymentReceived", String.class);
    public static final MessageType<String> HANDED_TO_COURIER = MessageType.of("handedToCourier", String.class);
    public static final MessageType<String> CANCEL = MessageType.of("cancel", String.class);

    private static final Logger log = LoggerFactory.getLogger(OrderDeliveryDefinition.class);

    /** Describes order delivery that can be cancelled until handover to the courier. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.RESERVING)
            .step(State.RESERVING, OrderDeliveryDefinition::reserveItems, State.WAITING_FOR_PAYMENT)
            .await(State.WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_RECEIVED, OrderDeliveryDefinition::onPayment, State.PACKING)
                .message(CANCEL, OrderDeliveryDefinition::onCancel, State.CANCELLING))
            .step(State.PACKING, OrderDeliveryDefinition::packOrder, State.WAITING_FOR_COURIER)
            .await(State.WAITING_FOR_COURIER, await -> await
                .message(HANDED_TO_COURIER, OrderDeliveryDefinition::onHandedToCourier, State.SHIPPED)
                .message(CANCEL, OrderDeliveryDefinition::onCancel, State.CANCELLING))
            .step(State.CANCELLING, OrderDeliveryDefinition::cancel, State.CANCELLED)
            .terminal(State.SHIPPED)
            .terminal(State.CANCELLED)
            .build();
    }

    private static void reserveItems(ProcessContext context) {
        log.info("Items of order {} reserved", context.businessKey().orElseThrow());
    }

    private static void packOrder(ProcessContext context) {
        log.info("Order {} packed", context.businessKey().orElseThrow());
    }

    /** Releases the reservation and refunds the payment by order id. */
    private static void cancel(ProcessContext context) {
        log.info("Items of order {} released, payment refunded", context.businessKey().orElseThrow());
    }

    private static void onPayment(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(PAYMENT_ID, message.payload());
    }

    private static void onHandedToCourier(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(COURIER, message.payload());
    }

    private static void onCancel(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(CANCELLATION_REASON, message.payload());
    }

    public enum State implements ProcessState {
        RESERVING,
        WAITING_FOR_PAYMENT,
        PACKING,
        WAITING_FOR_COURIER,
        CANCELLING,
        SHIPPED,
        CANCELLED
    }
}
