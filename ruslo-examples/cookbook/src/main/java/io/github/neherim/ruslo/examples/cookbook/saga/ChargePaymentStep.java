package io.github.neherim.ruslo.examples.cookbook.saga;

import io.github.neherim.ruslo.core.api.definition.BranchingStep;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

@Component
public class ChargePaymentStep implements BranchingStep<ChargePaymentStep.Result> {
    private final Payments payments;

    public ChargePaymentStep(Payments payments) {
        this.payments = payments;
    }

    /** Charges the order payment; a bank decline is a business result, not an error. */
    @Override
    public Result execute(ProcessContext context) {
        var approved = payments.charge(
                context.businessKey().orElseThrow(), context.getVariable(OrderFulfillmentDefinition.AMOUNT));
        return approved ? Result.CHARGED : Result.DECLINED;
    }

    /** Bank's answer to the charge. */
    public enum Result {
        CHARGED,
        DECLINED
    }
}
