package io.github.neherim.ruslo.examples.cookbook.transactionalstart;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

/**
 * Recipe: save application data and start a process atomically.
 * <p>
 * Task: when an order is created, write it to an application table and start its process. Neither an order without
 * a process (nobody would handle it) nor a process without an order (the step would find no data) is allowed.
 * <p>
 * How it works:
 * <ul>
 *   <li>{@link PurchaseOrderService#createOrder} is {@code @Transactional}: the row insert and
 *       {@code newProcess(...).start()} run in one transaction. The engine joins the application transaction, so on
 *       rollback neither the order nor the process is saved.</li>
 *   <li>The process starts running only after commit: the dispatcher does not see an uncommitted process.</li>
 *   <li>Step {@link ConfirmOrderStep} on the transactional {@code DEFAULT} worker changes the order status in the
 *       same transaction as the process transition: the table status and the process state do not diverge.</li>
 *   <li>The process business key is the order id: the process is found by it with
 *       {@code processes().byBusinessKey(...)}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Atomicity works only if the application and the engine share one {@code DataSource} and transaction
 *       manager. In Spring Boot the starter takes them from the context.</li>
 *   <li>Do not start the process after commit (in a {@code @TransactionalEventListener} or a separate call): a crash
 *       in between leaves the order without a process.</li>
 *   <li>Do not start the process in {@code REQUIRES_NEW}: the process is committed even if the order transaction
 *       rolls back.</li>
 * </ul>
 */
@Component
public class PurchaseOrderDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("PURCHASE_ORDER");
    public static final VariableType<String> ORDER_ID = VariableType.of("orderId", String.class);

    private final ConfirmOrderStep confirmOrderStep;

    public PurchaseOrderDefinition(ConfirmOrderStep confirmOrderStep) {
        this.confirmOrderStep = confirmOrderStep;
    }

    /** Describes order handling: confirmation changes the order status in the application table. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.CONFIRMING)
            .step(State.CONFIRMING, confirmOrderStep, State.CONFIRMED)
            .terminal(State.CONFIRMED)
            .build();
    }

    public enum State implements ProcessState {
        CONFIRMING,
        CONFIRMED
    }
}
