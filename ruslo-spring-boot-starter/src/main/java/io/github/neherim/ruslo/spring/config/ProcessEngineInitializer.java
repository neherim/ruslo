package io.github.neherim.ruslo.spring.config;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * Registers process definitions and starts the {@link ProcessEngine} when the application context starts.
 * <p>
 * Definitions are fetched from the context only at lifecycle start, after all singletons are created. So the
 * engine bean does not depend on {@link ProcessDefinition} beans, and steps and the beans they use can inject
 * {@link ProcessEngine} without circular dependencies.
 * <p>
 * The engine starts before and stops after web servers and message listeners (their phases are at least
 * {@code DEFAULT_PHASE - 2048}): incoming requests and messages, including during graceful shutdown, always find
 * the engine running.
 */
public class ProcessEngineInitializer implements SmartLifecycle {
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;
    private final ProcessEngine processEngine;
    private final ObjectProvider<ProcessDefinition> processDefinitions;
    private boolean definitionsRegistered;

    /**
     * Creates an initializer that only starts and stops the engine and registers no definitions.
     */
    public ProcessEngineInitializer(ProcessEngine processEngine) {
        this(processEngine, null);
    }

    /**
     * Creates an initializer that registers process definitions in the engine before the first start.
     *
     * @param processDefinitions definitions from the context; {@code null} to register none
     */
    public ProcessEngineInitializer(
            ProcessEngine processEngine,
            ObjectProvider<ProcessDefinition> processDefinitions
    ) {
        this.processEngine = Objects.requireNonNull(processEngine, "ProcessEngine must not be null");
        this.processDefinitions = processDefinitions;
    }

    /**
     * Registers process definitions (once) and starts the engine. Does nothing if the engine is already started.
     */
    @Override
    public synchronized void start() {
        if (processEngine.isStarted()) {
            return;
        }
        if (processDefinitions != null && !definitionsRegistered) {
            processEngine.registerDefinitions(processDefinitions.orderedStream().toList());
            definitionsRegistered = true;
        }
        processEngine.start();
    }

    @Override
    public synchronized void stop() {
        if (!processEngine.isStarted()) {
            return;
        }
        processEngine.close();
    }

    @Override
    public boolean isRunning() {
        return processEngine.isStarted();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
