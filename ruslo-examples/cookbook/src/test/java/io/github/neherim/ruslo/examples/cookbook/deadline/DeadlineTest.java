package io.github.neherim.ruslo.examples.cookbook.deadline;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class DeadlineTest {
    private static final Payment PAYMENT = new Payment("pay-1", new BigDecimal("50.00"));

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void confirmsReservationPaidBeforeDeadline() {
        var processId = startReservation();
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(SeatReservationDefinition.PAYMENT_DEADLINE.minusSeconds(1));
        var confirmed = emulator.sendMessageAndRunUntilStop(
                processId, SeatReservationDefinition.PAYMENT_RECEIVED, PAYMENT);

        assertTrue(confirmed.state().is(SeatReservationDefinition.State.CONFIRMED));
        assertEquals("pay-1",
                confirmed.variables().get(SeatReservationDefinition.PAYMENT_ID).orElseThrow());
    }

    @Test
    void releasesSeatWhenPaymentIsLate() {
        var processId = startReservation();
        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(SeatReservationDefinition.State.WAITING_FOR_PAYMENT));

        // Before the deadline the process waits, after it the seat is released
        emulator.fastForwardTime(SeatReservationDefinition.PAYMENT_DEADLINE.minus(Duration.ofSeconds(1)));
        assertTrue(emulator.runUntilStop(processId).state().is(SeatReservationDefinition.State.WAITING_FOR_PAYMENT));
        emulator.fastForwardTime(Duration.ofSeconds(1));
        var expired = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.COMPLETED, expired.status());
        assertTrue(expired.state().is(SeatReservationDefinition.State.EXPIRED));

        // The process does not accept a late payment: the sender handles it
        assertThrows(MessageNotExpectedException.class,
                () -> processEngine.sendMessage(processId, SeatReservationDefinition.PAYMENT_RECEIVED, PAYMENT));
    }

    private UUID startReservation() {
        return processEngine.newProcess(SeatReservationDefinition.TYPE)
                .variable(SeatReservationDefinition.SEAT, "A-" + UUID.randomUUID())
                .start();
    }
}
