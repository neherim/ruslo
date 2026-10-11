package io.github.neherim.ruslo.examples.cookbook.failandresume;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class IssueInvoiceStep implements ProcessStep {
    private final TaxService taxService;

    public IssueInvoiceStep(TaxService taxService) {
        this.taxService = taxService;
    }

    /** Calculates the tax and stores it in a variable; a calculation error goes to {@code onError}. */
    @Override
    public void execute(ProcessContext context) {
        var tax = taxService.calculateTax(
                context.getVariable(InvoiceDefinition.AMOUNT),
                context.getVariable(InvoiceDefinition.COUNTRY));
        context.setVariable(InvoiceDefinition.TAX, tax);
    }
}
