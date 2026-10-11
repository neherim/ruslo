package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ImportPartStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(ImportPartStep.class);

    private final ProcessEngine processEngine;

    public ImportPartStep(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Imports the rows of its part and reports completion to the parent. The step runs on a transactional worker,
     * so sending is atomic with the part transition: the report is sent exactly once.
     */
    @Override
    public void execute(ProcessContext context) {
        int fromRow = context.getVariable(FileImportPartDefinition.FROM_ROW);
        int toRow = context.getVariable(FileImportPartDefinition.TO_ROW);
        // Stub: a real application reads the file from storage and writes the rows to the DB here
        log.info("Importing rows {}-{} of {}", fromRow, toRow, context.getVariable(FileImportPartDefinition.FILE_NAME));

        UUID parentId = context.getVariable(FileImportPartDefinition.PARENT_ID);
        try {
            processEngine.sendMessage(parentId, FileImportDefinition.PART_DONE, new PartDone(toRow - fromRow + 1));
        } catch (MessageNotExpectedException ex) {
            // The parent no longer waits, e.g. it completed on the overall deadline: the part just completes
            log.warn("Parent process {} in state {} did not accept report for rows {}-{}",
                    parentId, ex.getCurrentState(), fromRow, toRow);
        }
    }
}
