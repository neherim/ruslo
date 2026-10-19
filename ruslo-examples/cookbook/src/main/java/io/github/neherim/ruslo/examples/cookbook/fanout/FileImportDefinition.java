package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

/**
 * Recipe: start several processes from a process and wait for them to complete.
 * <p>
 * Task: import a large file split into {@link #PART_COUNT} parts. Step {@link SplitFileStep} starts a
 * {@link FileImportPartDefinition} process per part and records how many parts remain. The parent moves to await
 * {@link State#WAITING_FOR_PARTS}, and each part sends it {@link #PART_DONE} when finished ({@link ImportPartStep}).
 * Handler {@link #onPartDone} counts the report and keeps waiting (the target is the await itself) until all parts
 * have reported. There is no separate subprocess API: these are ordinary processes exchanging messages.
 * <p>
 * Why a part's report cannot arrive before the parent starts waiting: the parts are created in the transaction of
 * step {@link SplitFileStep}, in which the parent moves to the await. The dispatcher sees the parts only after that
 * transaction commits. Hence the requirement: step {@link State#SPLITTING} must run on a transactional worker. If
 * the step fails after starting the parts, the transaction rolls back and no part is saved.
 * <p>
 * Out of scope of this recipe:
 * <ul>
 *   <li>Part errors. A failed part does not report, and the parent keeps waiting. Retry transient failures (recipe
 *       {@code retries}) or report a permanent error with a separate message from a fallback state.</li>
 *   <li>Zero parts. The parent would wait forever: route this case past the await with a {@code BranchingStep}.</li>
 *   <li>Duplicate reports. A manual retry of a part step sends its report again; if that matters, track reports by
 *       part number instead of a counter.</li>
 * </ul>
 */
@Component
public class FileImportDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("FILE_IMPORT");
    public static final VariableType<Integer> PART_COUNT = VariableType.of("partCount", Integer.class);
    public static final VariableType<Integer> REMAINING_PARTS = VariableType.of("remainingParts", Integer.class);
    /** Part report; the payload is the part number. */
    public static final MessageType<Integer> PART_DONE = MessageType.of("partDone", Integer.class);

    private final SplitFileStep splitFileStep;

    public FileImportDefinition(SplitFileStep splitFileStep) {
        this.splitFileStep = splitFileStep;
    }

    /** Describes the file import: start the parts and wait for their reports. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.SPLITTING)
            .step(State.SPLITTING, splitFileStep, State.WAITING_FOR_PARTS)
            .await(State.WAITING_FOR_PARTS, await -> await
                .message(PART_DONE, FileImportDefinition::onPartDone, routes -> routes
                    .on(Progress.WAIT, State.WAITING_FOR_PARTS)
                    .on(Progress.ALL_DONE, State.COMPLETED)))
            .terminal(State.COMPLETED)
            .build();
    }

    /** Counts a part report and reports whether all parts are done. */
    private static Progress onPartDone(ProcessContext context, IncomingMessage<Integer> message) {
        int remaining = context.getVariable(REMAINING_PARTS) - 1;
        context.setVariable(REMAINING_PARTS, remaining);
        return remaining == 0 ? Progress.ALL_DONE : Progress.WAIT;
    }

    public enum State implements ProcessState {
        SPLITTING,
        WAITING_FOR_PARTS,
        COMPLETED
    }

    /** Result of counting a part report. */
    public enum Progress {
        WAIT,
        ALL_DONE
    }
}
