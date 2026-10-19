package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImportPartStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(ImportPartStep.class);

    private final ProcessEngine processEngine;

    public ImportPartStep(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Imports its part and reports completion to the parent. The step runs on a transactional worker, so sending is
     * atomic with the part transition: the report is sent exactly once.
     */
    @Override
    public void execute(ProcessContext context) {
        int part = context.getVariable(FileImportPartDefinition.PART_NUMBER);
        log.info("Importing part {}", part);
        processEngine.sendMessage(context.getVariable(FileImportPartDefinition.PARENT_ID),
                FileImportDefinition.PART_DONE, part);
    }
}
