package io.github.neherim.ruslo.examples.quickstart;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.examples.quickstart.dto.PaymentConfirmed;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderService {
    private final ProcessEngine processEngine;

    public OrderService(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Creates an order and starts its process.
     *
     * @return the process id
     */
    public UUID createOrder(String customerId) {
        return processEngine.newProcess(OrderProcessDefinition.TYPE)
            .businessKey(customerId)
            .variable(Constants.CUSTOMER_ID, customerId)
            .start();
    }

    /**
     * Confirms the order payment.
     *
     * @param orderId the order process id
     * @throws MessageNotExpectedException if the process is not waiting for a payment now
     */
    public void confirmPayment(UUID orderId, String transactionId) {
        processEngine.sendMessage(orderId, Constants.PAYMENT_CONFIRMED, new PaymentConfirmed(transactionId));
    }

    /**
     * Cancels the order.
     *
     * @param orderId the order process id
     * @throws MessageNotExpectedException if the process does not accept a cancellation now
     */
    public void cancelOrder(UUID orderId, String reason) {
        processEngine.sendMessage(orderId, Constants.ORDER_CANCELLED, reason);
    }
}
