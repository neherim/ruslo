package io.github.neherim.ruslo.examples.cookbook.failandresume;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Operator actions on invoice processes. In an application a service endpoint or a support task calls them; without
 * code, the admin UI does the same.
 */
@Component
public class InvoiceOperations {
    private final ProcessEngine processEngine;
    private final ProcessEngineAdmin processEngineAdmin;

    public InvoiceOperations(ProcessEngine processEngine, ProcessEngineAdmin processEngineAdmin) {
        this.processEngine = processEngine;
        this.processEngineAdmin = processEngineAdmin;
    }

    /** Returns stopped processes: they wait for a fix and {@link #fixCountryAndResume}. */
    public List<ProcessInstanceSummary> findFailed() {
        return processEngine.processes()
            .byType(InvoiceDefinition.TYPE)
            .byStatus(ProcessStatus.FAILED)
            .list();
    }

    /** Returns processes still retrying a failed step: an early sign of a problem. */
    public List<ProcessInstanceSummary> findRetrying() {
        return processEngine.processes()
            .byType(InvoiceDefinition.TYPE)
            .byStatus(ProcessStatus.ACTIVE)
            .byAttempted()
            .list();
    }

    /**
     * Fixes the customer's country and resumes the stopped process: the failed step runs again.
     *
     * @throws ProcessStatusException if the process is no longer {@code FAILED}, for example another operator
     *                                resumed it
     */
    public void fixCountryAndResume(UUID processId, String country) {
        processEngineAdmin.setProcessVariable(processId, InvoiceDefinition.COUNTRY, country);
        processEngine.resumeFailedProcess(processId);
    }
}
