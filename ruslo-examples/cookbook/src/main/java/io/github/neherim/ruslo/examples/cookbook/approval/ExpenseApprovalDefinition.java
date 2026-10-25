package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Recipe: escalation, restarting the wait with a new deadline and a new recipient.
 * <p>
 * Task: an employee submits an expense report, their manager approves it. If the manager does not answer in time,
 * the approval goes to their manager with a new deadline; if there is no one above, the report expires.
 * <p>
 * How it works:
 * <ul>
 *   <li>Await {@link State#WAITING_FOR_DECISION} waits for message {@link #DECISION} with an {@code after}
 *       deadline.</li>
 *   <li>On the deadline step {@link State#ESCALATING} passes the approval to the manager ({@link #APPROVER}) and
 *       returns the process to step {@link State#REQUESTING}, which leads back to the await. Entering the await again
 *       restarts its {@code after} deadline: this is the only way to reset it. A deadline stored in a variable
 *       ({@code at}) moves without re-entering the await: recipe {@code deadline}.</li>
 *   <li>If there is no manager, the process ends in {@link State#EXPIRED}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>A decision from the previous approver that arrives after escalation is accepted too. If that is not
 *       acceptable, compare {@code approver} with {@link #APPROVER} in the handler and keep waiting on a mismatch.
 *       While the process moves to the new approver, {@code sendMessage} throws
 *       {@code MessageNotExpectedException}: the approval UI should offer to retry.</li>
 * </ul>
 */
@Component
public class ExpenseApprovalDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("EXPENSE_APPROVAL");
    public static final Duration DECISION_DEADLINE = Duration.ofDays(2);
    public static final VariableType<String> APPROVER = VariableType.of("approver", String.class);
    public static final VariableType<String> DECIDED_BY = VariableType.of("decidedBy", String.class);
    public static final MessageType<Decision> DECISION = MessageType.of("decision", Decision.class);

    private static final Logger log = LoggerFactory.getLogger(ExpenseApprovalDefinition.class);
    /** Stands for the org chart: employee to manager; the top manager has none. */
    private static final Map<String, String> MANAGERS = Map.of(
            "alice", "bob",
            "bob", "carol");

    /** Describes the approval: request, wait for a decision, escalation on a deadline. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.REQUESTING)
            .step(State.REQUESTING, ExpenseApprovalDefinition::requestApproval, State.WAITING_FOR_DECISION)
            .await(State.WAITING_FOR_DECISION, await -> await
                .message(DECISION, ExpenseApprovalDefinition::onDecision, routes -> routes
                    .on(Outcome.APPROVED, State.APPROVED)
                    .on(Outcome.REJECTED, State.REJECTED))
                .after(DECISION_DEADLINE, State.ESCALATING))
            .step(State.ESCALATING, ExpenseApprovalDefinition::escalate, routes -> routes
                .on(Escalation.ESCALATED, State.REQUESTING)
                .on(Escalation.NO_ONE_LEFT, State.EXPIRED))
            .terminal(State.APPROVED)
            .terminal(State.REJECTED)
            .terminal(State.EXPIRED)
            .build();
    }

    private static void requestApproval(ProcessContext context) {
        log.info("Approval of {} requested from {}", context.businessKey().orElseThrow(),
                context.getVariable(APPROVER));
    }

    private static Outcome onDecision(ProcessContext context, IncomingMessage<Decision> message) {
        context.setVariable(DECIDED_BY, message.payload().approver());
        return message.payload().approved() ? Outcome.APPROVED : Outcome.REJECTED;
    }

    /** Passes the approval to the current approver's manager, if there is one. */
    private static Escalation escalate(ProcessContext context) {
        var manager = MANAGERS.get(context.getVariable(APPROVER));
        if (manager == null) {
            return Escalation.NO_ONE_LEFT;
        }
        context.setVariable(APPROVER, manager);
        return Escalation.ESCALATED;
    }

    public enum State implements ProcessState {
        REQUESTING,
        WAITING_FOR_DECISION,
        ESCALATING,
        APPROVED,
        REJECTED,
        EXPIRED
    }

    /** Decision on the report. */
    public enum Outcome {
        APPROVED,
        REJECTED
    }

    /** Escalation result. */
    public enum Escalation {
        ESCALATED,
        NO_ONE_LEFT
    }
}
