package io.github.neherim.ruslo.examples.cookbook.saga;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Recipe: saga, undo what other systems have already done if a later step fails.
 * <p>
 * Task: fulfill an order across three services: reserve stock, charge the payment, schedule delivery. The services
 * share no transaction. If the bank declines the payment or delivery cannot be scheduled, the reservation and the
 * payment must be undone.
 * <p>
 * How it works:
 * <ul>
 *   <li>The happy path is three steps: {@link State#RESERVING_STOCK}, {@link State#CHARGING},
 *       {@link State#SCHEDULING_DELIVERY}.</li>
 *   <li>A bank decline is a business result: {@link ChargePaymentStep} returns
 *       {@link ChargePaymentStep.Result#DECLINED}, and the process goes to {@link State#COMPENSATING}.</li>
 *   <li>Technical errors are retried; when retries are exhausted, {@code fallback()} moves the process to the same
 *       {@link State#COMPENSATING}: it is the process {@code fallbackState}.</li>
 *   <li>{@link CompensateStep} undoes everything in reverse order: delivery, payment, reservation. Each undo is
 *       idempotent and keyed by the order id (the process business key), so the step does not need to know where
 *       the process failed, and retrying the step is safe.</li>
 *   <li>If the compensation itself fails, it is retried, and then the process stops in {@code FAILED}: the engine
 *       does not go to {@code fallbackState} from {@code fallbackState} itself. A human decides next (recipe
 *       {@code failandresume}).</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>An exception does not mean the external call did not happen: the response may have been lost after the
 *       charge. So compensation also undoes the failed step, and undo operations look things up by order id, not by
 *       ids stored in variables: after a step error its variables are not saved.</li>
 *   <li>Compensation can be split into steps ({@code REFUNDING → RELEASING}) so each is retried separately. Then
 *       their errors must not lead to {@code fallbackState}: otherwise {@code RELEASING} would return to
 *       {@code REFUNDING} on error, and the process would loop. The engine does not detect such indirect
 *       loops.</li>
 *   <li>Compensation is a new action, not a rollback: the customer may see a charge and a refund. Put steps that
 *       fail more often and are cheaper to undo first.</li>
 * </ul>
 */
@Component
public class OrderFulfillmentDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER_FULFILLMENT");
    public static final VariableType<String> ITEM = VariableType.of("item", String.class);
    public static final VariableType<BigDecimal> AMOUNT = VariableType.of("amount", BigDecimal.class);
    public static final VariableType<String> ADDRESS = VariableType.of("address", String.class);

    static final int MAX_RETRIES = 3;

    private final ReserveStockStep reserveStockStep;
    private final ChargePaymentStep chargePaymentStep;
    private final ScheduleDeliveryStep scheduleDeliveryStep;
    private final CompensateStep compensateStep;

    public OrderFulfillmentDefinition(
            ReserveStockStep reserveStockStep,
            ChargePaymentStep chargePaymentStep,
            ScheduleDeliveryStep scheduleDeliveryStep,
            CompensateStep compensateStep
    ) {
        this.reserveStockStep = reserveStockStep;
        this.chargePaymentStep = chargePaymentStep;
        this.scheduleDeliveryStep = scheduleDeliveryStep;
        this.compensateStep = compensateStep;
    }

    /** Describes order fulfillment: reservation, payment, delivery and compensation on a decline or failure. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .onError(ProcessErrorHandler.exponential(MAX_RETRIES, Duration.ofSeconds(1), Duration.ofMinutes(1)),
                    State.COMPENSATING)
            .initialState(State.RESERVING_STOCK)
            .step(State.RESERVING_STOCK, reserveStockStep, State.CHARGING)
            .step(State.CHARGING, chargePaymentStep, routes -> routes
                .on(ChargePaymentStep.Result.CHARGED, State.SCHEDULING_DELIVERY)
                .on(ChargePaymentStep.Result.DECLINED, State.COMPENSATING))
            .step(State.SCHEDULING_DELIVERY, scheduleDeliveryStep, State.COMPLETED)
            .step(State.COMPENSATING, compensateStep, State.CANCELLED)
            .terminal(State.COMPLETED)
            .terminal(State.CANCELLED)
            .build();
    }

    public enum State implements ProcessState {
        RESERVING_STOCK,
        CHARGING,
        SCHEDULING_DELIVERY,
        COMPENSATING,
        COMPLETED,
        CANCELLED
    }
}
