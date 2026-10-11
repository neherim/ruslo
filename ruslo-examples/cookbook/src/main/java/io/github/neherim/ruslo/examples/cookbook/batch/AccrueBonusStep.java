package io.github.neherim.ruslo.examples.cookbook.batch;

import io.github.neherim.ruslo.core.api.definition.BranchingStep;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AccrueBonusStep implements BranchingStep<AccrueBonusStep.Batch> {
    private final LoyaltyAccountRepository accountRepository;

    public AccrueBonusStep(LoyaltyAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Accrues the bonus to the next batch of accounts after the cursor and moves the cursor. The accrual and the new
     * cursor are committed in one transaction with the transition, so after a failure no batch is accrued twice.
     */
    @Override
    public Batch execute(ProcessContext context) {
        long cursor = Objects.requireNonNullElse(context.getVariable(BonusAccrualDefinition.CURSOR), 0L);
        var ids = accountRepository.findIdsAfter(cursor, BonusAccrualDefinition.BATCH_SIZE);
        if (ids.isEmpty()) {
            return Batch.DONE;
        }
        accountRepository.addPoints(ids, context.getVariable(BonusAccrualDefinition.BONUS_POINTS));
        int processed = Objects.requireNonNullElse(context.getVariable(BonusAccrualDefinition.PROCESSED), 0);
        context.setVariable(BonusAccrualDefinition.CURSOR, ids.getLast());
        context.setVariable(BonusAccrualDefinition.PROCESSED, processed + ids.size());
        return ids.size() < BonusAccrualDefinition.BATCH_SIZE ? Batch.DONE : Batch.MORE;
    }

    /** Whether unprocessed accounts remain. */
    public enum Batch {
        MORE,
        DONE
    }
}
