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
            throw new IllegalArgumentException("Process definition with type " + processType.name()
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

    /** Returns the declared type, or {@link ProcessType#of(String)} if it is not registered. */
    public ProcessType declaredType(String name) {
        var spec = specs.get(ProcessType.of(name));
        return spec != null ? spec.declaredType() : ProcessType.of(name);
    }

    /** Returns the declared state, or {@link ProcessState#of(String)} if the definition does not declare it. */
    public ProcessState declaredState(String type, String name) {
        var spec = specs.get(ProcessType.of(type));
        return spec != null ? spec.declaredState(name) : ProcessState.of(name);
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
        return new ProcessSpecInfo(spec.declaredType(), spec.getDescription(),
                spec.declaredState(spec.getInitialState().name()), states);
    }

    private ProcessSpecInfo.StateInfo mapStateToInfo(ProcessSpec spec, ProcessSpec.StateSpec state) {
        var description = spec.getStateDescription(state.state());
        var declared = spec.declaredState(state.state().name());
        return switch (state) {
            case ProcessSpec.StepState step -> new ProcessSpecInfo.StateInfo(declared, description,
                    ProcessSpecInfo.StateKind.STEP, Optional.of(step.worker()), Set.of(), Optional.empty(),
                    Optional.empty(), Optional.empty(),
                    transitions(spec, ProcessSpecInfo.TransitionTrigger.STEP, null, step.exit()));
            case ProcessSpec.AwaitState await -> {
                var transitions = new ArrayList<ProcessSpecInfo.TransitionInfo>();
                await.messages()
                    .forEach(message -> transitions.addAll(transitions(spec,
                            ProcessSpecInfo.TransitionTrigger.MESSAGE, message.type().name(), message.exit())));
                if (await.every() != null) {
                    transitions
                        .addAll(transitions(spec, ProcessSpecInfo.TransitionTrigger.EVERY, null, await.every().exit()));
                }
                if (await.after() != null) {
                    transitions
                        .addAll(transitions(spec, ProcessSpecInfo.TransitionTrigger.AFTER, null, await.after().exit()));
                }
                if (await.at() != null) {
                    transitions
                        .addAll(transitions(spec, ProcessSpecInfo.TransitionTrigger.AT, null, await.at().exit()));
                }
                yield new ProcessSpecInfo.StateInfo(declared, description, ProcessSpecInfo.StateKind.AWAIT,
                        Optional.of(WorkerId.DEFAULT),
                        await.messages().stream().map(e -> e.type().name()).collect(Collectors.toUnmodifiableSet()),
                        Optional.ofNullable(await.every()).map(every -> every.interval().period()),
                        Optional.ofNullable(await.after()).map(ProcessSpec.AfterEvent::delay),
                        Optional.ofNullable(await.at()).map(at -> at.timer().name()),
                        List.copyOf(transitions));
            }
            case ProcessSpec.TerminalState ignored -> new ProcessSpecInfo.StateInfo(declared, description,
                    ProcessSpecInfo.StateKind.TERMINAL, Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of());
        };
    }

    private static List<ProcessSpecInfo.TransitionInfo> transitions(
            ProcessSpec spec,
            ProcessSpecInfo.TransitionTrigger trigger,
            String messageType,
            ProcessSpec.Exit exit
    ) {
        return switch (exit) {
            case ProcessSpec.Target target -> List.of(new ProcessSpecInfo.TransitionInfo(trigger,
                    Optional.ofNullable(messageType), Optional.empty(), spec.declaredState(target.state().name())));
            case ProcessSpec.Routes routes -> routes.routes()
                .entrySet()
                .stream()
                .sorted(Comparator.comparingInt(route -> route.getKey().ordinal()))
                .map(route -> new ProcessSpecInfo.TransitionInfo(trigger, Optional.ofNullable(messageType),
                        Optional.of(route.getKey().name()),
                        spec.declaredState(route.getValue().name())))
                .toList();
        };
    }
}
