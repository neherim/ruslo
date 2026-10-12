package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class ApprovalTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoSpyBean
    private ApprovalNotifier notifier;

    @Test
    void approvesWithinDeadline() {
        var reportId = newReportId();
        var processId = startApproval(reportId, "alice");
        emulator.runUntilStop(processId);
        verify(notifier).requestApproval("alice", reportId);

        emulator.fastForwardTime(ExpenseApprovalDefinition.REMINDER_INTERVAL);
        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(ExpenseApprovalDefinition.State.WAITING_FOR_DECISION));
        verify(notifier).remind("alice", reportId);

        var approved = emulator.sendMessageAndRunUntilStop(processId, ExpenseApprovalDefinition.DECISION,
                new Decision("alice", true, "ok"));

        assertTrue(approved.state().is(ExpenseApprovalDefinition.State.APPROVED));
        assertEquals("alice",
                approved.variables().get(ExpenseApprovalDefinition.DECIDED_BY).orElseThrow());
    }

    @Test
    void remindsAndEscalatesToManager() {
        var reportId = newReportId();
        var processId = startApproval(reportId, "alice");
        emulator.runUntilStop(processId);

        // Reminders after one and two days; the deadline is 60 hours after entering the await
        emulator.fastForwardTime(Duration.ofDays(1));
        emulator.runUntilStop(processId);
        emulator.fastForwardTime(Duration.ofDays(1));
        emulator.runUntilStop(processId);
        emulator.fastForwardTime(Duration.ofHours(12));
        var escalated = emulator.runUntilStop(processId);

        assertTrue(escalated.state().is(ExpenseApprovalDefinition.State.WAITING_FOR_DECISION));
        assertEquals("bob",
                escalated.variables().get(ExpenseApprovalDefinition.APPROVER).orElseThrow());
        verify(notifier, times(2)).remind("alice", reportId);
        verify(notifier).requestApproval("bob", reportId);
        verify(notifier, never()).remind("bob", reportId);

        var rejected = emulator.sendMessageAndRunUntilStop(processId, ExpenseApprovalDefinition.DECISION,
                new Decision("bob", false, "Not a business expense"));

        assertTrue(rejected.state().is(ExpenseApprovalDefinition.State.REJECTED));
    }

    @Test
    void expiresWhenNoOneIsLeftToEscalate() {
        var processId = startApproval(newReportId(), "carol");
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ExpenseApprovalDefinition.DECISION_DEADLINE);
        var expired = emulator.runUntilStop(processId);

        assertTrue(expired.state().is(ExpenseApprovalDefinition.State.EXPIRED));
        verify(notifier, times(1)).requestApproval(any(), any());
    }

    private UUID startApproval(String reportId, String approver) {
        return processEngine.newProcess(ExpenseApprovalDefinition.TYPE)
            .businessKey(reportId)
            .variable(ExpenseApprovalDefinition.APPROVER, approver)
            .start();
    }

    private static String newReportId() {
        return "expense-" + UUID.randomUUID();
    }
}
