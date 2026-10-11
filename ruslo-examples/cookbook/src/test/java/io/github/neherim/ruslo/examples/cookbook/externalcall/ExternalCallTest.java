package io.github.neherim.ruslo.examples.cookbook.externalcall;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The emulator runs steps in the test thread and does not reproduce routing to workers: the test checks a step retry
 * after a failure, not which worker ran it.
 */
@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class ExternalCallTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @Test
    void retryAfterLostResponseDoesNotChargeTwice() {
        // The provider charged the money but the response was lost: the step fails and will be retried
        var charges = new ArrayList<Charge>();
        doAnswer(invocation -> {
            charges.add((Charge) invocation.callRealMethod());
            throw new IllegalStateException("Read timed out");
        }).doAnswer(invocation -> {
            charges.add((Charge) invocation.callRealMethod());
            return charges.getLast();
        }).when(paymentGateway).charge(any(), any(), any());
        var processId = startPayment(new BigDecimal("50.00"));

        var retrying = emulator.runUntilStop(processId);
        assertEquals(1, retrying.attempt());
        emulator.fastForwardTime(Duration.ofSeconds(5));
        var paid = emulator.runUntilStop(processId);

        assertTrue(paid.state().is(CardPaymentDefinition.State.PAID));
        // Both calls came with the same key, and the provider returned the first charge instead of a new one
        verify(paymentGateway, times(2)).charge(eq(processId.toString()), any(), any());
        assertEquals(charges.getFirst(), charges.getLast());
        assertEquals(charges.getFirst().chargeId(),
                paid.variables().get(CardPaymentDefinition.CHARGE_ID).orElseThrow());
    }

    @Test
    void declinedChargeIsNotRetried() {
        var declined = emulator.runUntilStop(startPayment(new BigDecimal("5000.00")));

        assertTrue(declined.state().is(CardPaymentDefinition.State.DECLINED));
        verify(paymentGateway, times(1)).charge(any(), any(), any());
    }

    @Test
    void stopsForOperatorWhenProviderStaysDown() {
        doAnswer(invocation -> {
            throw new IllegalStateException("Provider is unavailable");
        }).when(paymentGateway).charge(any(), any(), any());
        var processId = startPayment(new BigDecimal("50.00"));

        for (var delay : List.of(0, 5, 10, 20, 40, 80)) {
            emulator.fastForwardTime(Duration.ofSeconds(delay));
            emulator.runUntilStop(processId);
        }
        var failed = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.FAILED, failed.status());
        assertTrue(failed.state().is(CardPaymentDefinition.State.CHARGING));
        verify(paymentGateway, times(1 + CardPaymentDefinition.MAX_RETRIES)).charge(any(), any(), any());
    }

    private UUID startPayment(BigDecimal amount) {
        return processEngine.newProcess(CardPaymentDefinition.TYPE)
                .variable(CardPaymentDefinition.CARD_TOKEN, "tok_visa")
                .variable(CardPaymentDefinition.AMOUNT, amount)
                .start();
    }
}
