package io.github.neherim.ruslo.examples.cookbook.batch;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

/**
 * Recipe: process a large table in batches and continue from where it stopped after a failure.
 * <p>
 * Task: accrue a bonus to every loyalty program customer, a million rows in an application table. This cannot be
 * done in one transaction, and after a failure the process must neither start over nor lose the remaining rows.
 * <p>
 * How it works:
 * <ul>
 *   <li>Step {@link AccrueBonusStep} processes one batch of {@link #BATCH_SIZE} accounts and returns
 *       {@link AccrueBonusStep.Batch#MORE}. Its target is the same state {@link State#ACCRUING}: a transition
 *       to the same state is not a state change, so the step runs again without a new state history row.</li>
 *   <li>The cursor, the id of the last processed account, is stored in variable {@link #CURSOR}. Each batch is a
 *       separate transition with its own transaction.</li>
 *   <li>If a batch fails, only that batch is rolled back. After a retry or {@code resumeFailedProcess} the step
 *       reads the cursor of the last committed batch and continues from there.</li>
 *   <li>The last batch returns {@link AccrueBonusStep.Batch#DONE}, and the process completes.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Use a cursor on a unique ordered key, not {@code offset}: rows inserted or deleted during the pass would
 *       shift {@code offset}, and some rows would be skipped or processed twice.</li>
 *   <li>A batch is not processed twice only if its changes go to the engine's database on a transactional worker:
 *       then they commit together with the cursor (recipe {@code transactionalstart}). Otherwise a failed batch is
 *       processed again, so processing must be idempotent.</li>
 *   <li>The whole pass occupies one worker slot. Give many concurrent passes their own worker (recipe
 *       {@code externalcall}) or split one pass into parallel parts (recipe {@code fanout}).</li>
 * </ul>
 */
@Component
public class BonusAccrualDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("BONUS_ACCRUAL");
    public static final int BATCH_SIZE = 100;
    public static final VariableType<Long> LAST_ACCOUNT_ID = VariableType.of("lastAccountId", Long.class);
    public static final VariableType<Long> CURSOR = VariableType.of("cursor", Long.class);

    private final AccrueBonusStep accrueBonusStep;

    public BonusAccrualDefinition(AccrueBonusStep accrueBonusStep) {
        this.accrueBonusStep = accrueBonusStep;
    }

    /** Describes the bonus accrual: the step repeats batch by batch until all accounts are processed. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.ACCRUING)
            .step(State.ACCRUING, accrueBonusStep, routes -> routes
                .on(AccrueBonusStep.Batch.MORE, State.ACCRUING)
                .on(AccrueBonusStep.Batch.DONE, State.COMPLETED))
            .terminal(State.COMPLETED)
            .build();
    }

    public enum State implements ProcessState {
        ACCRUING,
        COMPLETED
    }
}
