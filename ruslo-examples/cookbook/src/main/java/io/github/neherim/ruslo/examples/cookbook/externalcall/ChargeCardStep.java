package io.github.neherim.ruslo.examples.cookbook.externalcall;

import io.github.neherim.ruslo.core.api.definition.BranchingStep;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

@Component
public class ChargeCardStep implements BranchingStep<ChargeCardStep.Result> {
    private final PaymentGateway paymentGateway;

    public ChargeCardStep(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    /**
     * Charges the card with the process id as the idempotency key: a retry after a failure gets the same charge
     * from the provider instead of creating a new one.
     */
    @Override
    public Result execute(ProcessContext context) {
        var approved = paymentGateway.charge(
                context.processId().toString(), context.getVariable(CardPaymentDefinition.AMOUNT));
        return approved ? Result.APPROVED : Result.DECLINED;
    }

    /** Bank's answer to the charge. */
    public enum Result {
        APPROVED,
        DECLINED
    }
}
