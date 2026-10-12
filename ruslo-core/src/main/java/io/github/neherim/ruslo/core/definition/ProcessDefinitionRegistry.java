package io.github.neherim.ruslo.core.definition;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessTypeException;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Registry of process definitions. Definitions may be registered at any point of the engine lifecycle;
 * reads use an immutable snapshot and need no synchronization.
 */
public class ProcessDefinitionRegistry {
    private final Predicate<WorkerId> workerRegistered;
    private volatile Map<ProcessTypeId, ProcessSpec> specs = Map.of();

    /**
     * Creates an empty registry.
     *
     * @param workerRegistered tells whether a worker with the id is registered in the engine
     */
    public ProcessDefinitionRegistry(Predicate<WorkerId> workerRegistered) {
        this.workerRegistered = Objects.requireNonNull(workerRegistered, "Worker predicate must not be null");
    }

    /** Creates a registry with the definitions, without checking workers. */
    public ProcessDefinitionRegistry(Collection<? extends ProcessDefinition> processDefinitions) {
        this(workerId -> true);
        registerAll(processDefinitions);
    }

    /**
     * Builds the process spec from the definition, validates it and registers it.
     *
     * @return the registered process type
     * @throws IllegalArgumentException if the type is already registered or the definition references an unregistered
     *                                  worker
     */
    public synchronized ProcessTypeId register(ProcessDefinition processDefinition) {
        Objects.requireNonNull(processDefinition, "ProcessDefinition must not be null");
        var processSpec = processDefinition.define(new ProcessSpecBuilder());
        var processType = processSpec.getType();
        var current = specs;
        if (current.containsKey(processType)) {
            throw new IllegalArgumentException("Process definition with type " + processType
                    + " already registered");
        }
        for (var state : processSpec.getAllStateSpecs()) {
            if (state instanceof ProcessSpec.StepState step && !workerRegistered.test(step.worker())) {
                throw new IllegalArgumentException("Worker is not registered: " + step.worker());
            }
        }
        var updated = new HashMap<>(current);
        updated.put(processType, processSpec);
        specs = Map.copyOf(updated);
        return processType;
    }

    /** Registers definitions in iteration order. */
    public void registerAll(Collection<? extends ProcessDefinition> processDefinitions) {
        Objects.requireNonNull(processDefinitions, "ProcessDefinitions must not be null").forEach(this::register);
    }

    /**
     * Returns the spec of the process type.
     *
     * @throws UnsupportedProcessTypeException if no definition of the type is registered
     */
    public ProcessSpec getProcessSpec(ProcessType processType) {
        var processDefinition = specs.get(ProcessType.of(processType.name()));
        if (processDefinition == null) {
            throw new UnsupportedProcessTypeException(processType.name());
        }
        return processDefinition;
    }

    /** Returns metadata of the registered definitions. */
    public List<ProcessSpecInfo> getProcessDefinitions() {
        return specs.values().stream().map(this::mapToInfo).toList();
    }

    private ProcessSpecInfo mapToInfo(ProcessSpec spec) {
        var states = spec.getAllStateSpecs()
            .stream()
            .map(s -> mapStateToInfo(spec, s))
            .collect(Collectors.toSet());
        return new ProcessSpecInfo(spec.getType(), spec.getDescription(), spec.getInitialState(), states);
    }

    private ProcessSpecInfo.StateInfo mapStateToInfo(ProcessSpec spec, ProcessSpec.StateSpec state) {
        var description = spec.getStateDescription(state.state());
        return switch (state) {
            case ProcessSpec.StepState step -> new ProcessSpecInfo.StateInfo(step.state(), description,
                    ProcessSpecInfo.StateKind.STEP, Optional.of(step.worker()), Set.of(), Optional.empty(),
                    Optional.empty(), transitions(ProcessSpecInfo.TransitionTrigger.STEP, null, step.exit()));
            case ProcessSpec.AwaitState await -> {
                var transitions = new ArrayList<ProcessSpecInfo.TransitionInfo>();
                await.messages()
                    .forEach(message -> transitions.addAll(transitions(
                            ProcessSpecInfo.TransitionTrigger.MESSAGE, message.type().name(), message.exit())));
                if (await.every() != null) {
                    transitions
                        .addAll(transitions(ProcessSpecInfo.TransitionTrigger.EVERY, null, await.every().exit()));
                }
                if (await.timeout() != null) {
                    transitions.add(new ProcessSpecInfo.TransitionInfo(ProcessSpecInfo.TransitionTrigger.TIMEOUT,
                            Optional.empty(), Optional.empty(), await.timeout().next()));
                }
                yield new ProcessSpecInfo.StateInfo(await.state(), description, ProcessSpecInfo.StateKind.AWAIT,
                        Optional.of(WorkerId.DEFAULT),
                        await.messages().stream().map(e -> e.type().name()).collect(Collectors.toUnmodifiableSet()),
                        Optional.ofNullable(await.timeout()).map(ProcessSpec.TimeoutEvent::delay),
                        Optional.ofNullable(await.every()).map(ProcessSpec.EveryEvent::interval),
                        List.copyOf(transitions));
            }
            case ProcessSpec.TerminalState terminal -> new ProcessSpecInfo.StateInfo(terminal.state(), description,
                    ProcessSpecInfo.StateKind.TERMINAL, Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                    List.of());
        };
    }

    private static List<ProcessSpecInfo.TransitionInfo> transitions(
            ProcessSpecInfo.TransitionTrigger trigger,
            String messageType,
            ProcessSpec.Exit exit
    ) {
        return switch (exit) {
            case ProcessSpec.Target target -> List.of(new ProcessSpecInfo.TransitionInfo(trigger,
                    Optional.ofNullable(messageType), Optional.empty(), target.state()));
            case ProcessSpec.Routes routes -> routes.routes()
                .entrySet()
                .stream()
                .sorted(Comparator.comparingInt(route -> route.getKey().ordinal()))
                .map(route -> new ProcessSpecInfo.TransitionInfo(trigger, Optional.ofNullable(messageType),
                        Optional.of(route.getKey().name()), route.getValue()))
                .toList();
        };
    }
}
