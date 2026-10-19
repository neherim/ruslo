package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
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
class ApprovalTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void approvesWithinDeadline() {
        var processId = startApproval("alice");
        emulator.runUntilStop(processId);

        var approved = emulator.sendMessageAndRunUntilStop(processId, ExpenseApprovalDefinition.DECISION,
                new Decision("alice", true));

        assertTrue(approved.state().is(ExpenseApprovalDefinition.State.APPROVED));
        assertEquals("alice", approved.variables().get(ExpenseApprovalDefinition.DECIDED_BY).orElseThrow());
    }

    @Test
    void escalatesToManagerAfterDeadline() {
        var processId = startApproval("alice");
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ExpenseApprovalDefinition.DECISION_DEADLINE);
        var escalated = emulator.runUntilStop(processId);

        assertTrue(escalated.state().is(ExpenseApprovalDefinition.State.WAITING_FOR_DECISION));
        assertEquals("bob", escalated.variables().get(ExpenseApprovalDefinition.APPROVER).orElseThrow());

        var rejected = emulator.sendMessageAndRunUntilStop(processId, ExpenseApprovalDefinition.DECISION,
                new Decision("bob", false));

        assertTrue(rejected.state().is(ExpenseApprovalDefinition.State.REJECTED));
    }

    @Test
    void expiresWhenNoOneIsLeftToEscalate() {
        var processId = startApproval("carol");
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ExpenseApprovalDefinition.DECISION_DEADLINE);
        var expired = emulator.runUntilStop(processId);

        assertTrue(expired.state().is(ExpenseApprovalDefinition.State.EXPIRED));
    }

    private UUID startApproval(String approver) {
        return processEngine.newProcess(ExpenseApprovalDefinition.TYPE)
            .businessKey("expense-" + UUID.randomUUID())
            .variable(ExpenseApprovalDefinition.APPROVER, approver)
            .start();
    }
}
