package io.github.neherim.ruslo.examples.cookbook.branching;

import io.github.neherim.ruslo.core.api.test.TestProcessContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step unit test without Spring, the engine or a database: {@link BranchingTest} checks where each outcome leads,
 * this test checks how the step picks the outcome. {@link TestProcessContext} stores variables as JSON, like the
 * engine, so the test catches a value that does not survive serialization.
 */
class CheckApplicationStepTest {
    private final CheckApplicationStep step = new CheckApplicationStep();

    @Test
    void approvesAmountUpToAutoApproveLimit() {
        var context = contextWithAmount(CheckApplicationStep.AUTO_APPROVE_LIMIT);

        assertEquals(CheckApplicationStep.Decision.APPROVE, step.execute(context));
        // The step wrote no variables
        assertEquals(Set.of("amount"), context.variableNames());
    }

    @Test
    void sendsAmountUpToMaxAmountToManualReview() {
        var context = contextWithAmount(CheckApplicationStep.MAX_AMOUNT);

        assertEquals(CheckApplicationStep.Decision.MANUAL_REVIEW, step.execute(context));
    }

    @Test
    void rejectsAmountAboveMaxAmountWithReason() {
        var context = contextWithAmount(CheckApplicationStep.MAX_AMOUNT + 1);

        assertEquals(CheckApplicationStep.Decision.REJECT, step.execute(context));
        assertEquals("Amount exceeds 100000", context.getVariable(LoanApplicationDefinition.REJECTION_REASON));
    }

    private static TestProcessContext contextWithAmount(long amount) {
        return TestProcessContext.builder()
            .variable(LoanApplicationDefinition.AMOUNT, amount)
            .build();
    }
}
