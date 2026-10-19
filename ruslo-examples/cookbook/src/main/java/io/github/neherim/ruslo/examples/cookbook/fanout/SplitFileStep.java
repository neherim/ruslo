package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class SplitFileStep implements ProcessStep {
    private final ProcessEngine processEngine;

    public SplitFileStep(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Starts a process per file part and records the number of parts that must report. The parts are saved in the
     * step transaction: if the step fails, none is saved.
     */
    @Override
    public void execute(ProcessContext context) {
        int parts = context.getVariable(FileImportDefinition.PART_COUNT);
        for (int part = 1; part <= parts; part++) {
            processEngine.newProcess(FileImportPartDefinition.TYPE)
                // Parts get the parent's correlation id, so logs show the whole job together
                .correlationId(context.correlationId())
                .variable(FileImportPartDefinition.PARENT_ID, context.processId())
                .variable(FileImportPartDefinition.PART_NUMBER, part)
                .start();
        }
        context.setVariable(FileImportDefinition.REMAINING_PARTS, parts);
    }
}
