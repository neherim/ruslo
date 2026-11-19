package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessAction;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

@Component
public class SplitFileStep implements ProcessAction {
    private final ProcessEngine processEngine;

    public SplitFileStep(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Starts a process per file part and records the number of parts that must report. The parts are saved in the
     * step transaction: if the step fails, none is saved.
     */
    @Override
    public void execute(ProcessContext ctx) {
        int parts = ctx.getVariable(FileImportDefinition.PART_COUNT);
        for (int part = 1; part <= parts; part++) {
            processEngine.newProcess(FileImportPartDefinition.TYPE)
                // Parts get the parent's correlation id, so logs show the whole job together
                .correlationId(ctx.correlationId())
                .variable(FileImportPartDefinition.PARENT_ID, ctx.processId())
                .variable(FileImportPartDefinition.PART_NUMBER, part)
                .start();
        }
        ctx.setVariable(FileImportDefinition.REMAINING_PARTS, parts);
    }
}
