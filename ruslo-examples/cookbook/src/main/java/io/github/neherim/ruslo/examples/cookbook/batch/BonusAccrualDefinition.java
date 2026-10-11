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
 * done in one transaction, and after a failure the process must neither start over (some customers would get the
 * bonus twice) nor lose the remaining ones.
 * <p>
 * How it works:
 * <ul>
 *   <li>Step {@link AccrueBonusStep} processes one batch of {@link #BATCH_SIZE} accounts and returns
 *       {@link AccrueBonusStep.Batch#MORE}. Its target is the same state {@link State#ACCRUING}: a step that
 *       targets itself runs again.</li>
 *   <li>The cursor, the id of the last processed account, is stored in variable {@link #CURSOR}. Each batch is a
 *       separate transition with its own transaction: the accrual, the cursor and the counter are committed
 *       together.</li>
 *   <li>If a batch fails, only that batch is rolled back. After a retry or {@code resumeFailedProcess} the step
 *       reads the cursor of the last committed batch and continues from there.</li>
 *   <li>The last, incomplete batch returns {@link AccrueBonusStep.Batch#DONE}, and the process completes.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Use a cursor on a unique ordered key, not {@code offset}: rows inserted or deleted during the pass would
 *       shift {@code offset}, and some rows would be skipped or processed twice.</li>
 *   <li>The batch and the cursor are atomic only on a transactional worker. On a non-transactional one a batch is
 *       processed again after a failure, so processing must be idempotent, for example with a mark in the row.</li>
 *   <li>A batch is one transaction: it holds row locks and a DB connection. Choose hundreds or thousands of rows,
 *       not the whole volume.</li>
 *   <li>Until the pass ends, the process occupies one worker slot: the next batch runs on the same node without
 *       going back to the queue. If many such passes run at once, move them to a separate transactional worker
 *       with {@code max-concurrency} so they do not crowd out other processes (recipe {@code ioworker}).</li>
 *   <li>To process batches in parallel rather than one by one, hand out ranges to part processes
 *       (recipe {@code fanout}).</li>
 * </ul>
 */
@Component
public class BonusAccrualDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("BONUS_ACCRUAL");
    public static final int BATCH_SIZE = 100;
    public static final VariableType<Integer> BONUS_POINTS = VariableType.of("bonusPoints", Integer.class);
    public static final VariableType<Long> CURSOR = VariableType.of("cursor", Long.class);
    public static final VariableType<Integer> PROCESSED = VariableType.of("processed", Integer.class);

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
