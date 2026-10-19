package io.github.neherim.ruslo.examples.cookbook.batch;

import io.github.neherim.ruslo.core.api.definition.BranchingStep;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AccrueBonusStep implements BranchingStep<AccrueBonusStep.Batch> {
    private static final Logger log = LoggerFactory.getLogger(AccrueBonusStep.class);

    /**
     * Processes the next batch of accounts after the cursor and moves the cursor. The new cursor is committed with
     * the transition, so after a failure the step continues from the last committed batch.
     */
    @Override
    public Batch execute(ProcessContext context) {
        long cursor = Objects.requireNonNullElse(context.getVariable(BonusAccrualDefinition.CURSOR), 0L);
        long lastId = context.getVariable(BonusAccrualDefinition.LAST_ACCOUNT_ID);
        long batchEnd = Math.min(cursor + BonusAccrualDefinition.BATCH_SIZE, lastId);
        log.info("Bonus accrued to accounts {}..{}", cursor + 1, batchEnd);
        context.setVariable(BonusAccrualDefinition.CURSOR, batchEnd);
        return batchEnd < lastId ? Batch.MORE : Batch.DONE;
    }

    /** Whether unprocessed accounts remain. */
    public enum Batch {
        MORE,
        DONE
    }
}
