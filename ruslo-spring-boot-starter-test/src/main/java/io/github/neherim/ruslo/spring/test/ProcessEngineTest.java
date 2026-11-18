package io.github.neherim.ruslo.spring.test;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS;

/**
 * Sets up a Spring Boot test to drive processes with {@link io.github.neherim.ruslo.core.api.ProcessEngineEmulator}.
 * <ul>
 *   <li>Registers a {@code ProcessEngineEmulator} bean.</li>
 *   <li>Disables the dispatcher: steps run only when the emulator runs them.</li>
 *   <li>Provides a mutable time source named {@code processEngineClock} unless a bean with that name exists.</li>
 *   <li>Resets the engine's time source before each test by default if it is a mutable clock.</li>
 * </ul>
 *
 * <pre>{@code
 * @ProcessEngineTest
 * @SpringBootTest
 * class OrderProcessTest {
 *     @Autowired ProcessEngineEmulator emulator;
 *     @Autowired ProcessEngine processEngine;
 *
 *     @Test void testOrderProcess() {
 *         var processId = processEngine.newProcess(OrderProcessDefinition.TYPE)
 *                 .variable("orderId", "123")
 *                 .start();
 *         var details = emulator.runUntilStop(processId);
 *         assertEquals(ProcessStatus.COMPLETED, details.status());
 *     }
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)
@TestPropertySource(properties = "ruslo.dispatcher.enabled=false")
@TestExecutionListeners(listeners = ProcessEngineTestExecutionListener.class, mergeMode = MERGE_WITH_DEFAULTS)
public @interface ProcessEngineTest {

    /** Enables resetting the engine's mutable time source to its initial time before each test method. */
    boolean resetTimeBeforeEachTest() default true;
}
