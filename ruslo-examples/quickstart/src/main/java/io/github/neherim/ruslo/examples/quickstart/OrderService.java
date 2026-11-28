package io.github.neherim.ruslo.examples.quickstart;

import io.github.neherim.ruslo.core.api.MessageDelivery;
import io.github.neherim.ruslo.core.api.ProcessEngine;
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
     * @return the delivery result; {@link MessageDelivery.NotExpected} if the process is not waiting for a payment now
     */
    public MessageDelivery confirmPayment(UUID orderId, String transactionId) {
        return processEngine.sendMessage(orderId, Constants.PAYMENT_CONFIRMED, new PaymentConfirmed(transactionId));
    }

    /**
     * Cancels the order.
     *
     * @param orderId the order process id
     * @return the delivery result; {@link MessageDelivery.NotExpected} if the process does not accept a cancellation now
     */
    public MessageDelivery cancelOrder(UUID orderId, String reason) {
        return processEngine.sendMessage(orderId, Constants.ORDER_CANCELLED, reason);
    }
}
