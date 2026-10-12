package io.github.neherim.ruslo.examples.cookbook.ioworker;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.springframework.stereotype.Component;

/**
 * Recipe: a dedicated non-transactional worker for long IO operations.
 * <p>
 * Task: render a document PDF through an external rendering service. The call takes seconds. On the transactional
 * {@code DEFAULT} worker the step would hold an open transaction and a DB connection all that time, and a few slow
 * calls would exhaust the connection pool.
 * <p>
 * How it works:
 * <ul>
 *   <li>Worker {@code io-worker} is declared in {@code application.yml} with
 *       {@code execution-mode: non_transactional}. A step on it runs without a transaction; a DB connection is
 *       taken only to save the result.</li>
 *   <li>{@code max-concurrency} limits concurrent steps of this worker on a node. It both protects the external
 *       service from overload and isolates it: a slow service does not occupy {@code DEFAULT} slots.</li>
 *   <li>Only step {@link State#RENDERING} is assigned to {@code io-worker} ({@code options.worker(...)}). Document
 *       validation and all await handlers stay on {@code DEFAULT}.</li>
 *   <li>The same worker can be declared as a bean instead of yml:
 *       {@code WorkerSpec.builder(IO_WORKER).nonTransactional().maxConcurrency(4).build()}. By default steps run on
 *       virtual threads; {@code customExecutor(...)} is needed only for a pool of your own.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>A non-transactional step runs at least once: if the node crashes or loses the lease during the call, the
 *       step runs again. The external call must be idempotent (recipe {@code externalcall}).</li>
 *   <li>Application DB changes from such a step are not atomic with the process transition. Move DB work to
 *       neighbouring steps on {@code DEFAULT}.</li>
 *   <li>If a worker used in a definition is not registered, the engine does not start: {@code build()} checks this
 *       up front.</li>
 * </ul>
 */
@Component
public class DocumentExportDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("DOCUMENT_EXPORT");
    public static final WorkerId IO_WORKER = new WorkerId("io-worker");
    public static final VariableType<String> DOCUMENT_ID = VariableType.of("documentId", String.class);
    public static final VariableType<String> FILE_URL = VariableType.of("fileUrl", String.class);

    private final ValidateDocumentStep validateDocumentStep;
    private final RenderDocumentStep renderDocumentStep;

    public DocumentExportDefinition(ValidateDocumentStep validateDocumentStep, RenderDocumentStep renderDocumentStep) {
        this.validateDocumentStep = validateDocumentStep;
        this.renderDocumentStep = renderDocumentStep;
    }

    /** Describes a document export: validation on {@code DEFAULT}, rendering on {@code io-worker}. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.VALIDATING)
            .step(State.VALIDATING, validateDocumentStep, State.RENDERING)
            .step(State.RENDERING, renderDocumentStep, State.COMPLETED,
                    options -> options.worker(IO_WORKER))
            .terminal(State.COMPLETED)
            .build();
    }

    public enum State implements ProcessState {
        VALIDATING,
        RENDERING,
        COMPLETED
    }
}
