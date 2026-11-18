package consumer;

import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RusloConsumerTest {

    @Test
    void runsJUnitWithThePublishedTestStarter() {
        assertTrue(ProcessEngineTest.class.isAnnotation());
    }
}
