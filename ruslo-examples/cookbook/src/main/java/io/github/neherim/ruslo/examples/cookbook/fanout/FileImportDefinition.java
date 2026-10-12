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
 * Task: import a large file in parts. Step {@link SplitFileStep} starts a {@link FileImportPartDefinition} process
 * per part and records how many parts remain. The parent moves to await {@link State#WAITING_FOR_PARTS}, and each
 * part sends it {@link #PART_DONE} when finished. Handler {@link #onPartDone} counts the report and keeps waiting
 * (the target is the await itself) until all parts have reported. There is no separate subprocess API: these are
 * ordinary processes exchanging messages.
 * <p>
 * Why a part's report cannot arrive before the parent starts waiting: the parts are created in the transaction of
 * step {@link SplitFileStep}, in which the parent moves to the await. The dispatcher sees the parts only after that
 * transaction commits. Hence the requirement: step {@link State#SPLITTING} must run on a transactional worker. If
 * the step fails after starting the parts, the transaction rolls back and no part is saved.
 * <p>
 * Out of scope of this recipe:
 * <ul>
 *   <li>Part errors. A failed part does not report, and the parent keeps waiting. Retry transient failures (recipe
 *       {@code retries}); a part can report a permanent error with a separate message from a fallback state
 *       ({@code ErrorResult.fallback()}).</li>
 *   <li>Zero parts. The parent would wait for reports that never come: route this case past the await with a
 *       separate branch ({@code BranchingStep}).</li>
 *   <li>Duplicate reports. A part reports exactly once, but a manual retry of its step sends the report again, and
 *       the counter counts it twice. If that matters, track reports by part number.</li>
 *   <li>An overall deadline via {@code timeout}. Then parts may report to an already completed parent:
 *       {@code sendMessage} in {@link ImportPartStep} throws {@code MessageNotExpectedException}, the step catches
 *       it, and the part simply completes. The rejection does not roll back the step transaction.</li>
 * </ul>
 */
@Component
public class FileImportDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("FILE_IMPORT");
    public static final VariableType<String> FILE_NAME = VariableType.of("fileName", String.class);
    public static final VariableType<Integer> ROW_COUNT = VariableType.of("rowCount", Integer.class);
    public static final VariableType<Integer> REMAINING_PARTS = VariableType.of("remainingParts", Integer.class);
    public static final VariableType<Integer> IMPORTED_ROWS = VariableType.of("importedRows", Integer.class);
    public static final MessageType<PartDone> PART_DONE = MessageType.of("partDone", PartDone.class);

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
                .message(PART_DONE, this::onPartDone, routes -> routes
                    .on(Progress.WAIT, State.WAITING_FOR_PARTS)
                    .on(Progress.ALL_DONE, State.COMPLETED)))
            .terminal(State.COMPLETED)
            .build();
    }

    /** Counts a part report and reports whether all parts are done. */
    private Progress onPartDone(ProcessContext context, IncomingMessage<PartDone> message) {
        context.setVariable(IMPORTED_ROWS, context.getVariable(IMPORTED_ROWS) + message.payload().importedRows());
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
