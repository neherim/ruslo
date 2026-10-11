package io.github.neherim.ruslo.examples.cookbook.failandresume;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class FailAndResumeTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @Autowired
    private InvoiceOperations invoiceOperations;
    @MockitoSpyBean
    private TaxService taxService;

    @Test
    void stopsOnInvalidDataAndContinuesAfterFix() {
        var processId = startInvoice("XX");

        var failed = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.FAILED, failed.status());
        assertTrue(failed.state().is(InvoiceDefinition.State.ISSUING));
        assertEquals("UnsupportedCountryException: Unsupported country: XX", failed.lastError().orElseThrow());
        assertTrue(ids(invoiceOperations.findFailed()).contains(processId));

        invoiceOperations.fixCountryAndResume(processId, "DE");
        var issued = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.COMPLETED, issued.status());
        assertEquals(new BigDecimal("19.00"),
                issued.variables().get(InvoiceDefinition.TAX).orElseThrow());
        assertFalse(ids(invoiceOperations.findFailed()).contains(processId));
    }

    @Test
    void retriesTemporaryFailureInsteadOfStopping() {
        doThrow(new IllegalStateException("Tax service is unavailable"))
                .when(taxService).calculateTax(any(), any());
        var processId = startInvoice("DE");

        var retrying = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.ACTIVE, retrying.status());
        assertEquals(1, retrying.attempt());
        assertTrue(ids(invoiceOperations.findRetrying()).contains(processId));
    }

    private UUID startInvoice(String country) {
        return processEngine.newProcess(InvoiceDefinition.TYPE)
                .businessKey("order-" + UUID.randomUUID())
                .variable(InvoiceDefinition.AMOUNT, new BigDecimal("100.00"))
                .variable(InvoiceDefinition.COUNTRY, country)
                .start();
    }

    private static List<UUID> ids(List<ProcessInstanceSummary> processes) {
        return processes.stream().map(ProcessInstanceSummary::id).toList();
    }
}
