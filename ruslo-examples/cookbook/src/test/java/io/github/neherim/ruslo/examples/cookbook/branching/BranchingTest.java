package io.github.neherim.ruslo.examples.cookbook.branching;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class BranchingTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void approvesSmallAmountAutomatically() {
        var approved = emulator.runUntilStop(startApplication(500));

        assertEquals(ProcessStatus.COMPLETED, approved.status());
        assertEquals(LoanApplicationDefinition.State.APPROVED.id(), approved.state());
    }

    @Test
    void rejectsAmountAboveLimit() {
        var rejected = emulator.runUntilStop(startApplication(250_000));

        assertEquals(LoanApplicationDefinition.State.REJECTED.id(), rejected.state());
        assertEquals("Amount exceeds 100000",
                rejected.variables().get(LoanApplicationDefinition.REJECTION_REASON).orElseThrow());
    }

    @Test
    void approvesAfterManualReview() {
        var processId = startApplication(20_000);
        var waiting = emulator.runUntilStop(processId);
        assertEquals(LoanApplicationDefinition.State.MANUAL_REVIEW.id(), waiting.state());

        var approved = emulator.sendMessageAndRunUntilStop(processId, LoanApplicationDefinition.REVIEW_DECISION,
                new ReviewDecision(true, "Stable income"));

        assertEquals(LoanApplicationDefinition.State.APPROVED.id(), approved.state());
    }

    @Test
    void rejectsAfterManualReview() {
        var processId = startApplication(20_000);
        emulator.runUntilStop(processId);

        var rejected = emulator.sendMessageAndRunUntilStop(processId, LoanApplicationDefinition.REVIEW_DECISION,
                new ReviewDecision(false, "Insufficient income"));

        assertEquals(LoanApplicationDefinition.State.REJECTED.id(), rejected.state());
        assertEquals("Insufficient income",
                rejected.variables().get(LoanApplicationDefinition.REJECTION_REASON).orElseThrow());
    }

    private UUID startApplication(long amount) {
        return processEngine.newProcess(LoanApplicationDefinition.TYPE)
            .variable(LoanApplicationDefinition.AMOUNT, amount)
            .start();
    }
}
