package io.github.neherim.ruslo.examples.cookbook.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stub for approver notifications: an email, a chat message or a tracker task. */
@Component
public class ApprovalNotifier {
    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifier.class);

    /** Asks to approve an expense report. */
    public void requestApproval(String approver, String reportId) {
        log.info("Approval of expense report {} requested from {}", reportId, approver);
    }

    /** Reminds about a pending report. */
    public void remind(String approver, String reportId) {
        log.info("Reminder about expense report {} sent to {}", reportId, approver);
    }
}
