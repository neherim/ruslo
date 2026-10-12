package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class SplitFileStep implements ProcessStep {
    public static final int PART_SIZE = 100;

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
        int rowCount = context.getVariable(FileImportDefinition.ROW_COUNT);
        int parts = Math.ceilDiv(rowCount, PART_SIZE);
        for (int i = 0; i < parts; i++) {
            int fromRow = i * PART_SIZE + 1;
            processEngine.newProcess(FileImportPartDefinition.TYPE)
                // Parts get the parent's correlation id, so logs show the whole job together
                .correlationId(context.correlationId())
                .variable(FileImportPartDefinition.PARENT_ID, context.processId())
                .variable(FileImportPartDefinition.FILE_NAME, context.getVariable(FileImportDefinition.FILE_NAME))
                .variable(FileImportPartDefinition.FROM_ROW, fromRow)
                .variable(FileImportPartDefinition.TO_ROW, Math.min(fromRow + PART_SIZE - 1, rowCount))
                .start();
        }
        context.setVariable(FileImportDefinition.REMAINING_PARTS, parts);
        context.setVariable(FileImportDefinition.IMPORTED_ROWS, 0);
    }
}
