package io.github.neherim.ruslo.examples.cookbook.webhook;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class SubmitVerificationStep implements ProcessStep {
    private final VerificationProvider verificationProvider;

    public SubmitVerificationStep(VerificationProvider verificationProvider) {
        this.verificationProvider = verificationProvider;
    }

    /** Submits the application to the service; the service returns the process business key in the callback. */
    @Override
    public void execute(ProcessContext context) {
        verificationProvider.submit(context.businessKey().orElseThrow());
    }
}
