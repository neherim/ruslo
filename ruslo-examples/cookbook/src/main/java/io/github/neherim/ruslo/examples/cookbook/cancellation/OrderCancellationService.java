package io.github.neherim.ruslo.examples.cookbook.cancellation;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import org.springframework.stereotype.Component;

/**
 * Accepts an order cancellation from the customer. In an application a REST controller calls it: {@code ACCEPTED}
 * maps to {@code 202}, {@code BUSY} to {@code 409} with an offer to retry, {@code TOO_LATE} to {@code 409} without
 * a retry, {@code MANUAL} to {@code 409} with a referral to support. A {@code sendMessageSync} rejection is a
 * {@link MessageNotExpectedException} carrying the process status and state. The engine throws it after leaving its
 * transaction, so a caught rejection does not roll back the application transaction.
 */
@Component
public class OrderCancellationService {
    private final ProcessEngine processEngine;

    public OrderCancellationService(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Cancels the order unless it has been handed to the courier. The cancellation is handled synchronously: on
     * {@code ACCEPTED} the process is already in {@code CANCELLING}; the dispatcher releases the reservation and
     * refunds the payment.
     *
     * @param orderId order id, also the process business key
     */
    public Result cancel(String orderId, String reason) {
        var process = processEngine.processes()
            .byType(OrderDeliveryDefinition.TYPE)
            .byBusinessKey(orderId)
            .single()
            .orElseThrow(() -> new IllegalArgumentException("Unknown order: " + orderId));
        try {
            processEngine.sendMessageSync(process.id(), OrderDeliveryDefinition.CANCEL, reason);
            return Result.ACCEPTED;
        } catch (MessageNotExpectedException ex) {
            return switch (ex.getStatus()) {
                // An active process is running a step: after it, the process is in an await that accepts
                // cancel or in a terminal state
                case ACTIVE -> Result.BUSY;
                case COMPLETED -> Result.TOO_LATE;
                case FAILED -> Result.MANUAL;
            };
        }
    }

    /** Answer to a cancellation request. */
    public enum Result {
        /** The order is being cancelled. */
        ACCEPTED,
        /** The order is between stages; retry in a few seconds. */
        BUSY,
        /** The order has already been handed to the courier or cancelled. */
        TOO_LATE,
        /** The order is stuck after an error; support cancels it manually. */
        MANUAL
    }
}
