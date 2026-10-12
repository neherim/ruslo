package io.github.neherim.ruslo.examples.cookbook.batch;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class BatchTest {
    private static final int ACCOUNTS = 250;
    private static final int BONUS = 10;

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @Autowired
    private JdbcClient jdbcClient;
    @MockitoSpyBean
    private LoyaltyAccountRepository accountRepository;

    @BeforeEach
    void createAccounts() {
        jdbcClient.sql("delete from loyalty_account").update();
        LongStream.rangeClosed(1, ACCOUNTS).forEach(accountRepository::insert);
    }

    @Test
    void accruesBonusToAllAccountsInBatches() {
        var completed = emulator.runUntilStop(startAccrual());

        assertTrue(completed.state().is(BonusAccrualDefinition.State.COMPLETED));
        assertEquals(List.of(BONUS), distinctPoints());
        // 250 accounts make three batches: 100, 100 and an incomplete 50
        verify(accountRepository, times(3)).addPoints(anyList(), anyInt());
        assertEquals(250,
                completed.variables().get(BonusAccrualDefinition.PROCESSED).orElseThrow());
    }

    @Test
    void continuesFromCursorAfterFailure() {
        // The second batch fails: the first is already committed, the second is rolled back entirely
        doCallRealMethod()
            .doThrow(new IllegalStateException("Lock timeout"))
            .doCallRealMethod()
            .when(accountRepository)
            .addPoints(anyList(), anyInt());
        var processId = startAccrual();

        var failed = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.FAILED, failed.status());
        assertEquals(100L,
                failed.variables().get(BonusAccrualDefinition.CURSOR).orElseThrow());
        assertEquals(100, countWithPoints(BONUS));
        assertEquals(150, countWithPoints(0));

        processEngine.resumeFailedProcess(processId);
        var completed = emulator.runUntilStop(processId);

        // No account got the bonus twice
        assertTrue(completed.state().is(BonusAccrualDefinition.State.COMPLETED));
        assertEquals(List.of(BONUS), distinctPoints());
    }

    private UUID startAccrual() {
        return processEngine.newProcess(BonusAccrualDefinition.TYPE)
            .variable(BonusAccrualDefinition.BONUS_POINTS, BONUS)
            .start();
    }

    private List<Integer> distinctPoints() {
        return jdbcClient.sql("select distinct points from loyalty_account").query(Integer.class).list();
    }

    private int countWithPoints(int points) {
        return jdbcClient.sql("select count(*) from loyalty_account where points = ?")
            .param(points)
            .query(Integer.class)
            .single();
    }
}
