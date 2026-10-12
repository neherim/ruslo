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
 *   <li>Step {@link CancelOrderStep} does not know at which stage the cancellation arrived: release and refund are
 *       keyed by the order id (business key) and do nothing if there is no reservation or payment.</li>
 *   <li>{@link OrderCancellation} accepts the cancellation with {@code sendMessageSync}: the transition to
 *       {@link State#CANCELLING} is committed before the method returns, so the customer gets a definite answer
 *       at once.</li>
 * </ul>
 * <p>
 * What to do if the process is in a step. Only an await accepts messages; early messages are not buffered. If the
 * process is running a step (or is queued for one), {@code sendMessageSync} throws
 * {@code MessageNotExpectedException}, and {@link OrderCancellation} returns {@code BUSY}: retry in a few seconds.
 * A step always moves on, either to an await that accepts cancellation or to a terminal state where it is too late,
 * so a retry gets a final answer. Keep steps short: a step retried after errors answers {@code BUSY} for the whole
 * retry period.
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>{@code sendMessage} instead of {@code sendMessageSync} only queues the cancellation. If a payment was queued
 *       before it, the process moves to {@link State#PACKING}, and the cancellation is deleted with the await queue
 *       even though {@code sendMessage} succeeded. The customer sees "cancelled", and the order ships.</li>
 *   <li>{@code sendMessageSync} handles the cancellation out of queue order, and an already accepted payment is
 *       deleted unhandled. That is why the cancel step refunds by order id, not by a variable the payment handler
 *       would have written.</li>
 *   <li>Do not cancel inside an application transaction: an exception from the cancel handler marks it
 *       rollback-only even if caught. Engine rejections ({@code MessageNotExpectedException},
 *       {@code ProcessStatusException}) do not mark the transaction.</li>
 *   <li>A process in {@code FAILED} is not handled synchronously ({@code ProcessStatusException}). An operator
 *       cancels such an order: in the admin UI, Change state → {@link State#CANCELLING}.</li>
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

    private final ReserveItemsStep reserveItemsStep;
    private final PackOrderStep packOrderStep;
    private final CancelOrderStep cancelOrderStep;

    public OrderDeliveryDefinition(
            ReserveItemsStep reserveItemsStep,
            PackOrderStep packOrderStep,
            CancelOrderStep cancelOrderStep
    ) {
        this.reserveItemsStep = reserveItemsStep;
        this.packOrderStep = packOrderStep;
        this.cancelOrderStep = cancelOrderStep;
    }

    /** Describes order delivery that can be cancelled until handover to the courier. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.RESERVING)
            .step(State.RESERVING, reserveItemsStep, State.WAITING_FOR_PAYMENT)
            .await(State.WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_RECEIVED, OrderDeliveryDefinition::onPayment, State.PACKING)
                .message(CANCEL, OrderDeliveryDefinition::onCancel, State.CANCELLING))
            .step(State.PACKING, packOrderStep, State.WAITING_FOR_COURIER)
            .await(State.WAITING_FOR_COURIER, await -> await
                .message(HANDED_TO_COURIER, OrderDeliveryDefinition::onHandedToCourier, State.SHIPPED)
                .message(CANCEL, OrderDeliveryDefinition::onCancel, State.CANCELLING))
            .step(State.CANCELLING, cancelOrderStep, State.CANCELLED)
            .terminal(State.SHIPPED)
            .terminal(State.CANCELLED)
            .build();
    }

    /** Stores the order payment. */
    private static void onPayment(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(PAYMENT_ID, message.payload());
    }

    /** Stores the courier the order was handed to. */
    private static void onHandedToCourier(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(COURIER, message.payload());
    }

    /** Stores the cancellation reason. */
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
