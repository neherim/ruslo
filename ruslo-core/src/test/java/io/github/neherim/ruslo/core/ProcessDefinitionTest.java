package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessTypeException;
import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ProcessDefinitionTest extends BaseTest {

    @Test
    void shouldUseInitialStepAsDefaultInitialState() {
        var processEngine = newProcessEngineWith(new SimpleProcessDefinition());

        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
        var process = processEngine.processes().byId(processId).singleDetails().orElseThrow();

        Assertions.assertTrue(process.state().is(SimpleProcessDefinition.State.INIT));
    }

    @Test
    void shouldFailWhenProcessTypeIsNotRegistered() {
        var processEngine = getDefaultProcessEngine();

        Assertions.assertThrows(UnsupportedProcessTypeException.class,
                () -> processEngine.newProcess(SimpleProcessDefinition.TYPE).start());
    }
}
