package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recipe: human approval with reminders and escalation on a deadline.
 * <p>
 * Task: an employee submits an expense report, their manager approves it. While there is no decision, the manager
 * gets a reminder once a day. If the manager does not answer in time, the approval goes to their manager; if there is
 * no one above, the report expires.
 * <p>
 * How it works:
 * <ul>
 *   <li>Step {@link RequestApprovalStep} asks the current approver ({@link #APPROVER}) for a decision.</li>
 *   <li>Await {@link State#WAITING_FOR_DECISION} declares three events. Message {@link #DECISION} ends the
 *       approval. {@code every} targeting the await itself sends a reminder and keeps waiting; the deadline does
 *       not move. {@code timeout} leads to escalation.</li>
 *   <li>Step {@link EscalateStep} passes the approval to the manager and returns the process to
 *       {@link State#REQUESTING}. The wait starts over: a new deadline, reminders go to the new approver.</li>
 *   <li>If there is no manager, the process ends in {@link State#EXPIRED}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Do not make the deadline a multiple of the reminder interval. At equal times {@code every} fires before
 *       {@code timeout}, and a needless reminder would go out right before escalation. Here the deadline is
 *       60 hours: reminders after one and two days, then escalation.</li>
 *   <li>To restart the wait, go to a step that leads back to the await. An event targeting the await itself does
 *       not reset the deadline, and {@code timeout} cannot target its own await.</li>
 *   <li>A decision from the previous approver that arrives after escalation is accepted too: the process checks
 *       only the message type. If that is not acceptable, compare {@code approver} from the decision with
 *       {@link #APPROVER} in the handler and return a "keep waiting" outcome that targets the await itself.</li>
 *   <li>While the process moves to the new approver (steps {@link State#ESCALATING} and {@link State#REQUESTING}),
 *       {@code sendMessage} with a decision may throw {@code MessageNotExpectedException}: early messages are not
 *       buffered. The approval UI should show an error and offer to retry.</li>
 * </ul>
 */
@Component
public class ExpenseApprovalDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("EXPENSE_APPROVAL");
    public static final Duration REMINDER_INTERVAL = Duration.ofDays(1);
    public static final Duration DECISION_DEADLINE = Duration.ofHours(60);
    public static final VariableType<String> APPROVER = VariableType.of("approver", String.class);
    public static final VariableType<String> DECIDED_BY = VariableType.of("decidedBy", String.class);
    public static final VariableType<String> COMMENT = VariableType.of("comment", String.class);
    public static final MessageType<Decision> DECISION = MessageType.of("decision", Decision.class);

    private final RequestApprovalStep requestApprovalStep;
    private final ApprovalHandlers approvalHandlers;
    private final EscalateStep escalateStep;

    public ExpenseApprovalDefinition(RequestApprovalStep requestApprovalStep,
                                     ApprovalHandlers approvalHandlers,
                                     EscalateStep escalateStep) {
        this.requestApprovalStep = requestApprovalStep;
        this.approvalHandlers = approvalHandlers;
        this.escalateStep = escalateStep;
    }

    /** Describes the approval: request, wait for a decision with reminders, escalation on a deadline. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .initialState(State.REQUESTING)
                .step(State.REQUESTING, requestApprovalStep, State.WAITING_FOR_DECISION)
                .await(State.WAITING_FOR_DECISION, await -> await
                        .message(DECISION, approvalHandlers::onDecision, routes -> routes
                                .on(ApprovalHandlers.Outcome.APPROVED, State.APPROVED)
                                .on(ApprovalHandlers.Outcome.REJECTED, State.REJECTED))
                        .every(REMINDER_INTERVAL, approvalHandlers::remind, State.WAITING_FOR_DECISION)
                        .timeout(DECISION_DEADLINE, State.ESCALATING))
                .step(State.ESCALATING, escalateStep, routes -> routes
                        .on(EscalateStep.Escalation.ESCALATED, State.REQUESTING)
                        .on(EscalateStep.Escalation.NO_ONE_LEFT, State.EXPIRED))
                .terminal(State.APPROVED)
                .terminal(State.REJECTED)
                .terminal(State.EXPIRED)
                .build();
    }

    public enum State implements ProcessState {
        REQUESTING,
        WAITING_FOR_DECISION,
        ESCALATING,
        APPROVED,
        REJECTED,
        EXPIRED
    }
}
