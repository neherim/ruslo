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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(approved.state().is(LoanApplicationDefinition.State.APPROVED));
    }

    @Test
    void rejectsAmountAboveLimit() {
        var rejected = emulator.runUntilStop(startApplication(250_000));

        assertTrue(rejected.state().is(LoanApplicationDefinition.State.REJECTED));
        assertEquals("Amount exceeds 100000",
                rejected.variables().get(LoanApplicationDefinition.REJECTION_REASON).orElseThrow());
    }

    @Test
    void approvesAfterManualReview() {
        var processId = startApplication(20_000);
        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(LoanApplicationDefinition.State.MANUAL_REVIEW));

        var approved = emulator.sendMessageAndRunUntilStop(processId, LoanApplicationDefinition.REVIEW_DECISION,
                new ReviewDecision("alice", true, "Stable income"));

        assertTrue(approved.state().is(LoanApplicationDefinition.State.APPROVED));
        assertEquals("alice",
                approved.variables().get(LoanApplicationDefinition.REVIEWER).orElseThrow());
    }

    @Test
    void rejectsAfterManualReview() {
        var processId = startApplication(20_000);
        emulator.runUntilStop(processId);

        var rejected = emulator.sendMessageAndRunUntilStop(processId, LoanApplicationDefinition.REVIEW_DECISION,
                new ReviewDecision("bob", false, "Insufficient income"));

        assertTrue(rejected.state().is(LoanApplicationDefinition.State.REJECTED));
        assertEquals("Insufficient income",
                rejected.variables().get(LoanApplicationDefinition.REJECTION_REASON).orElseThrow());
    }

    private UUID startApplication(long amount) {
        return processEngine.newProcess(LoanApplicationDefinition.TYPE)
                .variable(LoanApplicationDefinition.APPLICANT, "John Smith")
                .variable(LoanApplicationDefinition.AMOUNT, amount)
                .start();
    }
}
