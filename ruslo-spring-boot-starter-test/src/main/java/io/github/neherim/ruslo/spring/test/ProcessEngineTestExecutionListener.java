package io.github.neherim.ruslo.spring.test;

import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.TestExecutionListener;

/**
 * Resets the engine clock to the initial time before each test method when enabled by {@link ProcessEngineTest}.
 * Spring reuses test contexts, so without the reset time moved in one test would leak into the next.
 * Contexts without {@link ProcessEngineEmulator} are not affected.
 */
public class ProcessEngineTestExecutionListener implements TestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        var configuration = TestContextAnnotationUtils.findMergedAnnotation(testContext.getTestClass(),
                ProcessEngineTest.class);
        if (configuration != null && configuration.resetTimeBeforeEachTest() && testContext.hasApplicationContext()) {
            testContext.getApplicationContext()
                .getBeanProvider(ProcessEngineEmulator.class)
                .ifAvailable(ProcessEngineEmulator::resetTime);
        }
    }
}
