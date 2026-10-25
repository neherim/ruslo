package io.github.neherim.ruslo.examples.cookbook.externalcall;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The emulator runs steps in the test thread and does not reproduce routing to workers: the test checks the process
 * result, not which worker ran the step.
 */
@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class ExternalCallTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void paysApprovedCharge() {
        var paid = emulator.runUntilStop(startPayment(new BigDecimal("50.00")));

        assertEquals(CardPaymentDefinition.State.PAID.id(), paid.state());
    }

    @Test
    void declinedChargeIsAnOutcomeNotAnError() {
        var declined = emulator.runUntilStop(startPayment(new BigDecimal("5000.00")));

        assertEquals(CardPaymentDefinition.State.DECLINED.id(), declined.state());
    }

    private UUID startPayment(BigDecimal amount) {
        return processEngine.newProcess(CardPaymentDefinition.TYPE)
            .variable(CardPaymentDefinition.AMOUNT, amount)
            .start();
    }
}
