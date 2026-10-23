package io.github.neherim.ruslo.examples.cookbook.deadline;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class DeadlineTest {
    private static final String PAYMENT_ID = "pay-1";
    private static final Instant START = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant DUE = START.plus(SeatReservationDefinition.PAYMENT_TIME);

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void confirmsReservationPaidBeforeDeadline() {
        var processId = startReservation();
        emulator.runUntilStop(processId);

        emulator.setTime(DUE.minusSeconds(1));
        var confirmed = emulator.sendMessageAndRunUntilStop(
                processId, SeatReservationDefinition.PAYMENT_RECEIVED, PAYMENT_ID);

        assertTrue(confirmed.state().is(SeatReservationDefinition.State.CONFIRMED));
        assertEquals(PAYMENT_ID,
                confirmed.variables().get(SeatReservationDefinition.PAYMENT_ID).orElseThrow());
    }

    @Test
    void releasesSeatWhenPaymentIsLate() {
        var processId = startReservation();
        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(SeatReservationDefinition.State.WAITING_FOR_PAYMENT));
        assertEquals(DUE, waiting.variables().get(SeatReservationDefinition.PAYMENT_DUE).orElseThrow());

        // Before the deadline the process waits, after it the seat is released
        emulator.setTime(DUE.minusSeconds(1));
        assertTrue(emulator.runUntilStop(processId).state().is(SeatReservationDefinition.State.WAITING_FOR_PAYMENT));
        emulator.setTime(DUE);
        var expired = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.COMPLETED, expired.status());
        assertTrue(expired.state().is(SeatReservationDefinition.State.EXPIRED));

        // The process does not accept a late payment: the sender handles it
        assertThrows(MessageNotExpectedException.class,
                () -> processEngine.sendMessage(processId, SeatReservationDefinition.PAYMENT_RECEIVED, PAYMENT_ID));
    }

    @Test
    void extendedDeadlineMovesTimer() {
        var processId = startReservation();
        emulator.runUntilStop(processId);

        var extended = DUE.plus(Duration.ofMinutes(10));
        emulator.sendMessageAndRunUntilStop(processId, SeatReservationDefinition.EXTEND_PAYMENT_DUE, extended);
        emulator.setTime(DUE);
        assertTrue(emulator.runUntilStop(processId).state().is(SeatReservationDefinition.State.WAITING_FOR_PAYMENT));

        emulator.setTime(extended);
        assertTrue(emulator.runUntilStop(processId).state().is(SeatReservationDefinition.State.EXPIRED));
    }

    private UUID startReservation() {
        emulator.setTime(START);
        return processEngine.newProcess(SeatReservationDefinition.TYPE)
            .variable(SeatReservationDefinition.SEAT, "A-" + UUID.randomUUID())
            .start();
    }
}
