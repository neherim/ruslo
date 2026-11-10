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

        assertEquals(ExpenseApprovalDefinition.State.APPROVED, approved.state());
        assertEquals("alice", approved.variables().find(ExpenseApprovalDefinition.DECIDED_BY).orElseThrow());
    }

    @Test
    void escalatesToManagerAfterDeadline() {
        var processId = startApproval("alice");
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ExpenseApprovalDefinition.DECISION_DEADLINE);
        var escalated = emulator.runUntilStop(processId);

        assertEquals(ExpenseApprovalDefinition.State.WAITING_FOR_DECISION, escalated.state());
        assertEquals("bob", escalated.variables().find(ExpenseApprovalDefinition.APPROVER).orElseThrow());

        var rejected = emulator.sendMessageAndRunUntilStop(processId, ExpenseApprovalDefinition.DECISION,
                new Decision("bob", false));

        assertEquals(ExpenseApprovalDefinition.State.REJECTED, rejected.state());
    }

    @Test
    void expiresWhenNoOneIsLeftToEscalate() {
        var processId = startApproval("carol");
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ExpenseApprovalDefinition.DECISION_DEADLINE);
        var expired = emulator.runUntilStop(processId);

        assertEquals(ExpenseApprovalDefinition.State.EXPIRED, expired.state());
    }

    private UUID startApproval(String approver) {
        return processEngine.newProcess(ExpenseApprovalDefinition.TYPE)
            .businessKey("expense-" + UUID.randomUUID())
            .variable(ExpenseApprovalDefinition.APPROVER, approver)
            .start();
    }
}
