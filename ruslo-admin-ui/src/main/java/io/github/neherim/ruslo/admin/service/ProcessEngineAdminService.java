package io.github.neherim.ruslo.admin.service;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Admin UI operations on top of {@link ProcessEngine} and {@link ProcessEngineAdmin}. Exceptions of the engine
 * propagate; invalid JSON input throws {@link IllegalArgumentException}.
 */
public class ProcessEngineAdminService {
    private static final Duration OVERDUE_AFTER = Duration.ofMinutes(1);

    private final Clock clock = Clock.systemUTC();
    private final ProcessEngine processEngine;
    private final ProcessEngineAdmin management;

    public ProcessEngineAdminService(ProcessEngine processEngine, ProcessEngineAdmin management) {
        this.processEngine = processEngine;
        this.management = management;
    }

    /**
     * Returns the dashboard summary: counters of unfinished processes by type and engine instances. A process is
     * overdue if it is {@code ACTIVE} and its scheduled time passed more than a minute ago, as on the process page;
     * the boundary is truncated to the minute so it can be passed to the process list filter.
     *
     * @param zone browser time zone for the overdue boundary
     */
    public DashboardResponse dashboard(ZoneId zone) {
        var overdueAt = clock.instant().minus(OVERDUE_AFTER).truncatedTo(ChronoUnit.MINUTES);
        var typeCounts = new LinkedHashMap<String, DashboardResponse.TypeCounts>();
        long active = 0;
        long failed = 0;
        long overdue = 0;
        for (var counts : management.countIncompleteProcessesByType(overdueAt)) {
            typeCounts.put(counts.type().name(),
                    new DashboardResponse.TypeCounts(counts.active(), counts.failed(), counts.overdue()));
            active += counts.active();
            failed += counts.failed();
            overdue += counts.overdue();
        }
        var instances = management.listApplicationInstances()
            .stream()
            .map(instance -> new DashboardResponse.InstanceResponse(instance.id(), instance.leaseCount(),
                    instance.expiresIn().toSeconds(), instance.current()))
            .toList();
        return new DashboardResponse(active, failed, overdue,
                LocalDateTime.ofInstant(overdueAt, zone).toString(), zone.getId(), typeCounts, instances);
    }

    /**
     * Returns the registered process definitions sorted by type.
     */
    public List<DefinitionResponse> definitions() {
        return management.listProcessSpecifications()
            .stream()
            .map(this::toDefinitionResponse)
            .sorted(Comparator.comparing(DefinitionResponse::type))
            .toList();
    }

    /**
     * Returns the states of the {@code typeName} definition for the state change form, or only
     * {@code currentState} if no such definition is registered.
     */
    public List<DefinitionResponse.StateResponse> statesFor(String typeName, String currentState) {
        return definitions().stream()
            .filter(definition -> definition.type().equals(typeName))
            .findFirst()
            .map(DefinitionResponse::states)
            .orElse(List.of(new DefinitionResponse.StateResponse(currentState, null, "UNKNOWN", null,
                    List.of(), null)));
    }

    /**
     * Returns the state and transition graph of the {@code typeName} definition, if it is registered. Nodes are
     * labeled with state descriptions, edges with the transition event and outcome.
     */
    public Optional<ProcessGraphResponse> graph(String typeName) {
        return findDefinition(typeName).map(definition -> {
            var states = definition.states()
                .stream()
                .sorted(Comparator.comparing(state -> state.state().name()))
                .toList();
            var nodes = states.stream()
                .map(state -> new ProcessGraphResponse.Node(state.state().name(), state.description(),
                        state.kind().name(), state.workerId().map(WorkerId::name).orElse(null)))
                .toList();
            var edges = states.stream()
                .flatMap(state -> state.transitions()
                    .stream()
                    .map(transition -> new ProcessGraphResponse.Edge(state.state().name(),
                            transition.target().name(), transition.trigger().name(),
                            edgeLabel(state, transition))))
                .toList();
            return new ProcessGraphResponse(definition.initialState().name(), nodes, edges);
        });
    }

    /**
     * Returns one page of processes matching the search form.
     */
    public ProcessPageResponse searchProcesses(ProcessSearchRequest request) {
        var limit = request.pageLimit();
        var offset = request.pageOffset();
        var items = request.applyTo(processEngine.processes())
            .list(limit + 1, offset)
            .stream()
            .map(this::toProcessSummaryResponse)
            .toList();
        var hasNext = items.size() > limit;
        var pageItems = hasNext ? items.subList(0, limit) : items;
        return new ProcessPageResponse(pageItems, hasNext, limit, offset);
    }

    /**
     * Returns the process page data, if the process exists.
     */
    public Optional<ProcessDetailsResponse> process(UUID processId) {
        return processEngine.processes().byId(processId).singleDetails().map(this::toProcessDetailsResponse);
    }

    /**
     * Starts a process and returns its id.
     *
     * @param variables JSON object of process variables
     */
    public UUID startProcess(
            String type,
            String businessKey,
            String variables
    ) {
        var builder = processEngine.newProcess(ProcessType.of(type));
        if (hasText(businessKey)) {
            builder.businessKey(businessKey);
        }
        parseVariablesObject(variables).forEach(builder::variable);
        return builder.start();
    }

    /**
     * Sends a message to the process.
     *
     * @param jsonValue message value as JSON
     */
    public void sendMessage(UUID processId, String name, String jsonValue) {
        var value = parseJsonValue(jsonValue);
        management.sendMessage(processId, name, value);
    }

    /**
     * Sets a process variable.
     *
     * @param jsonValue variable value as JSON
     */
    public void setVariable(UUID processId, String name, String jsonValue) {
        management.setProcessVariable(processId, VariableType.of(name), parseJsonValue(jsonValue));
    }

    /**
     * Removes a process variable.
     *
     * @return {@code true} if the variable existed
     */
    public boolean removeVariable(UUID processId, String name) {
        return management.removeProcessVariable(processId, name);
    }

    /**
     * Resumes a process in {@code FAILED}: the failed step or event runs again without a state change.
     *
     * @throws io.github.neherim.ruslo.core.api.exception.ProcessStatusException if the process is not in {@code FAILED}
     */
    public void retryCurrentStep(UUID processId) {
        processEngine.resumeFailedProcess(processId);
    }

    /**
     * Moves the process to {@code state}; the dispatcher continues its execution.
     */
    public void changeState(UUID processId, String state) {
        management.moveProcessToState(processId, ProcessState.of(state));
    }

    private DefinitionResponse toDefinitionResponse(ProcessSpecInfo definition) {
        return new DefinitionResponse(
                definition.type().name(),
                definition.description().orElse(null),
                definition.initialState().name(),
                definition.states()
                    .stream()
                    .map(state -> new DefinitionResponse.StateResponse(state.state().name(),
                            state.description().equals(state.state().name()) ? null : state.description(),
                            state.kind().name(), state.workerId().map(WorkerId::name).orElse(null),
                            state.acceptedMessageTypes().stream().sorted().toList(),
                            timerDelay(state)))
                    .sorted(Comparator.comparing(DefinitionResponse.StateResponse::name))
                    .toList());
    }

    private String edgeLabel(ProcessSpecInfo.StateInfo state, ProcessSpecInfo.TransitionInfo transition) {
        var event = switch (transition.trigger()) {
            case STEP -> null;
            case MESSAGE -> transition.messageType().orElse(null);
            case EVERY -> "every " + state.everyInterval().map(this::shortDuration).orElse("");
            case TIMEOUT -> "timeout " + state.timeoutDelay().map(this::shortDuration).orElse("");
        };
        var outcome = transition.outcome().orElse(null);
        if (event == null) return outcome;
        return outcome == null ? event : event + " / " + outcome;
    }

    private String shortDuration(Duration duration) {
        return duration.toString().substring(2).toLowerCase(Locale.ROOT);
    }

    private ProcessSummaryResponse toProcessSummaryResponse(ProcessInstanceSummary process) {
        return new ProcessSummaryResponse(
                process.id(),
                process.type().name(),
                process.state().name(),
                process.status(),
                process.createdAt(),
                process.scheduledAt().orElse(null),
                process.businessKey().orElse(null));
    }

    private ProcessDetailsResponse toProcessDetailsResponse(ProcessInstanceDetails process) {
        var definition = findDefinition(process.type().name());
        var stateInfo = definition
            .flatMap(def -> def.states()
                .stream()
                .filter(state -> state.state().name().equals(process.state().name()))
                .findFirst())
            .orElse(null);
        return new ProcessDetailsResponse(
                process.id(),
                process.type().name(),
                process.state().name(),
                stateInfo == null || stateInfo.description().equals(stateInfo.state().name())
                        ? null
                        : stateInfo.description(),
                process.status(),
                process.scheduledAt().orElse(null),
                process.businessKey().orElse(null),
                process.correlationId(),
                process.attempt(),
                process.lastError().orElse(null),
                stateHistory(process, definition.orElse(null)),
                process.variables().asJsonMap(),
                stateInfo == null ? "UNKNOWN" : stateInfo.kind().name(),
                stateInfo == null ? List.of() : acceptedMessages(process, stateInfo),
                stateInfo == null ? null : timerDelay(stateInfo));
    }

    private List<ProcessDetailsResponse.AcceptedMessage> acceptedMessages(
            ProcessInstanceDetails process,
            ProcessSpecInfo.StateInfo state
    ) {
        return state.acceptedMessageTypes()
            .stream()
            .sorted()
            .map(name -> new ProcessDetailsResponse.AcceptedMessage(name,
                    management.messagePayloadTemplate(process.type(), process.state(), name).orElse("{}")))
            .toList();
    }

    /**
     * A transition is manual if the definition does not declare it, as on the diagram.
     * Without a registered definition no transition is marked.
     */
    private List<StateHistoryResponse> stateHistory(ProcessInstanceDetails process, ProcessSpecInfo definition) {
        var declared = new HashSet<String>();
        if (definition != null) {
            definition.states()
                .forEach(state -> state.transitions()
                    .forEach(transition -> declared.add(state.state().name() + "\u0000" + transition.target().name())));
        }
        var history = process.stateHistory();
        var result = new ArrayList<StateHistoryResponse>(history.size());
        for (int i = 0; i < history.size(); i++) {
            var state = history.get(i).state().name();
            var manual = definition != null && i > 0
                    && !declared.contains(history.get(i - 1).state().name() + "\u0000" + state);
            result.add(new StateHistoryResponse(state, history.get(i).changedAt(), manual));
        }
        return result;
    }

    private String timerDelay(ProcessSpecInfo.StateInfo state) {
        return state.everyInterval().or(state::timeoutDelay).map(Object::toString).orElse(null);
    }

    private Optional<ProcessSpecInfo> findDefinition(String typeName) {
        return management.listProcessSpecifications()
            .stream()
            .filter(definition -> definition.type().name().equals(typeName))
            .findFirst();
    }

    private Map<String, Object> parseVariablesObject(String variables) {
        if (!hasText(variables)) {
            return Map.of();
        }
        try {
            return JsonUtils.DEFAULT_OBJECT_MAPPER.readValue(variables,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Invalid variables JSON object", ex);
        }
    }

    private Object parseJsonValue(String jsonValue) {
        try {
            return JsonUtils.DEFAULT_OBJECT_MAPPER.readValue(jsonValue, Object.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Invalid JSON value", ex);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
