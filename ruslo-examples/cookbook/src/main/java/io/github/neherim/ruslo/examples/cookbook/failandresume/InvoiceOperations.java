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
 * Operator actions on invoice processes, called by a service endpoint or a support task.
 */
@Component
public class InvoiceOperations {
    private final ProcessEngine processEngine;
    private final ProcessEngineAdmin processEngineAdmin;

    public InvoiceOperations(ProcessEngine processEngine, ProcessEngineAdmin processEngineAdmin) {
        this.processEngine = processEngine;
        this.processEngineAdmin = processEngineAdmin;
    }

    /** Returns stopped processes awaiting {@link #fixCountryAndResume} or {@link #cancelFailedInvoice}. */
    public List<ProcessInstanceSummary> findFailed() {
        return processEngine.processes()
            .byType(InvoiceDefinition.TYPE)
            .byStatus(ProcessStatus.FAILED)
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
        processEngine.resumeFailedProcess(processId).orThrow();
    }

    /**
     * Resumes the stopped process at the cancellation step. The dispatcher cancels the invoice after commit.
     *
     * @throws ProcessStatusException if the process is no longer {@code FAILED}
     */
    public void cancelFailedInvoice(UUID processId) {
        processEngine.resumeFailedProcess(processId, InvoiceDefinition.State.CANCEL_INVOICE).orThrow();
    }
}
