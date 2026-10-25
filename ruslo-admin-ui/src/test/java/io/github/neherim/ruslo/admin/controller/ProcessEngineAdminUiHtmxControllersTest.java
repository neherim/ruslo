package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.DashboardResponse;
import io.github.neherim.ruslo.admin.dto.DefinitionResponse;
import io.github.neherim.ruslo.admin.dto.ProcessDetailsResponse;
import io.github.neherim.ruslo.admin.dto.StateHistoryResponse;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessEngineAdminUiHtmxControllersTest {

    @Test
    void dashboardShouldLinkCountersToFilteredProcessListAndShowInstances() {
        var service = new DashboardAdminService();
        var controller = new DashboardAdminController(service);
        var model = new ConcurrentModel();

        var viewName = controller.dashboardContent("Europe/Berlin", model);
        var html = renderDashboard(model);

        assertThat(viewName).isEqualTo("ruslo-admin/dashboard/fragments :: dashboard");
        assertThat(service.zone).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(html)
            .contains("href=\"processes?status=FAILED\"")
            .contains("href=\"processes?status=ACTIVE&amp;nextRunAtTo=2026-10-11T10:15&amp;timeZone=Europe/Berlin\"")
            .contains("href=\"processes?typeName=ORDER&amp;status=FAILED\"")
            .contains("metric-value numeric is-failed")
            .contains("<strong>4</strong> failed")
            .contains("<strong>0</strong> active")
            .contains("data-diagram-type=\"ORDER\"")
            .contains("data-diagram-type=\"TICKET\"")
            .contains("data-start-type=\"TICKET\"")
            .contains("this node")
            .contains("expires in 25s")
            .contains("expired 3s ago")
            .doesNotContain("Ready to start")
            .doesNotContain("bpmn");
    }

    @Test
    void dashboardShouldExplainMissingInstances() {
        var controller = new DashboardAdminController(new DashboardAdminService(List.of()));
        var model = new ConcurrentModel();

        controller.dashboardContent(null, model);
        var html = renderDashboard(model);

        assertThat(html).contains("the dispatcher is not running on any node");
    }

    private static String renderDashboard(ConcurrentModel model) {
        var context = new Context();
        context.setVariables(model.asMap());
        return templateEngine().process("ruslo-admin/dashboard/fragments", Set.of("dashboard"), context);
    }

    @Test
    void stateOptionsShouldExposeAllRegisteredStatesWhenTypeIsAny() {
        var controller = new ProcessesAdminController(new DefinitionsAdminService());
        var model = new ConcurrentModel();

        var viewName = controller.definitionStateOptions(null, model);

        assertThat(viewName).isEqualTo("ruslo-admin/processes/fragments :: stateFilterOptions");
        assertThat(model.getAttribute("states")).isEqualTo(List.of("CANCELLED", "CREATED", "PAID", "WAITING"));
    }

    @Test
    void stateOptionsShouldExposeOnlySelectedProcessTypeStates() {
        var controller = new ProcessesAdminController(new DefinitionsAdminService());
        var model = new ConcurrentModel();

        controller.definitionStateOptions("ORDER", model);

        assertThat(model.getAttribute("states")).isEqualTo(List.of("CANCELLED", "CREATED", "PAID"));
    }

    @Test
    void processDetailsRefreshShouldExposeTabCounts() {
        var processId = UUID.randomUUID();
        var process = new ProcessDetailsResponse(
                processId,
                "ORDER",
                "CREATED",
                null,
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                null,
                "biz-1",
                "trace-1",
                0,
                null,
                List.of(
                        new StateHistoryResponse("CREATED", Instant.parse("2026-05-01T10:00:00Z"), false),
                        new StateHistoryResponse("PAID", Instant.parse("2026-05-01T10:01:00Z"), false),
                        new StateHistoryResponse("CANCELLED", Instant.parse("2026-05-01T10:02:00Z"), false)),
                Map.of(
                        "amount", "42",
                        "customer", "{\"name\":\"Ada\"}"),
                "AWAIT", List.of("payment", "shipping"), null, List.of());
        var controller = new ProcessesAdminController(new ProcessDetailsAdminService(process));
        var response = new MockHttpServletResponse();

        var view = controller.processDetails(processId, null, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(view.getViewName()).isEqualTo("ruslo-admin/process-details/fragments :: processDetailsContent");
        var model = view.getModelMap();
        assertThat(model.getAttribute("process")).isSameAs(process);
        assertThat(process.variablesCount()).isEqualTo(2);
        assertThat(process.acceptedMessageTypes()).containsExactly("payment", "shipping");
        assertThat(process.historyCount()).isEqualTo(3);
        assertThat(model.getAttribute("states")).isEqualTo(List.of(
                new DefinitionResponse.StateResponse("CREATED", null, "STEP", "default", List.of(), null),
                new DefinitionResponse.StateResponse("PAID", null, "STEP", "default", List.of(), null),
                new DefinitionResponse.StateResponse("CANCELLED", null, "STEP", "default", List.of(), null)));
    }

    @Test
    void processDetailsPollingShouldAnswerNoContentWhileProcessIsUnchanged() {
        var processId = UUID.randomUUID();
        var process = processDetails(processId, "CREATED", 0);
        var controller = new ProcessesAdminController(new ProcessDetailsAdminService(process));

        var unchangedResponse = new MockHttpServletResponse();
        var unchanged = controller.processDetails(processId, process.version(), unchangedResponse);
        var changedResponse = new MockHttpServletResponse();
        var changed = controller.processDetails(
                processId, processDetails(processId, "CREATED", 1).version(), changedResponse);

        assertThat(unchanged).isNull();
        assertThat(unchangedResponse.getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(changed.getModelMap().getAttribute("process")).isSameAs(process);
        assertThat(changedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void processDetailsVersionShouldTrackMutableFieldsOnly() {
        var processId = UUID.randomUUID();
        var process = processDetails(processId, "CREATED", 0);

        assertThat(processDetails(processId, "CREATED", 0).version()).isEqualTo(process.version());
        assertThat(processDetails(processId, "PAID", 0).version()).isNotEqualTo(process.version());
        assertThat(processDetails(processId, "CREATED", 1).version()).isNotEqualTo(process.version());
    }

    @Test
    void processDetailsRefreshShouldReturnNotFoundForUnknownProcess() {
        var controller = new ProcessesAdminController(new DefinitionsAdminService() {
            @Override
            public Optional<ProcessDetailsResponse> process(UUID processId) {
                return Optional.empty();
            }
        });

        assertThatThrownBy(() -> controller.processDetails(UUID.randomUUID(), null, new MockHttpServletResponse()))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void retryCurrentStepShouldTriggerProcessDetailsRefresh() {
        var processId = UUID.randomUUID();
        var adminService = new RetryAdminService();
        var controller = new ProcessesAdminController(adminService);
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.retryCurrentStep(processId, new MockHttpServletRequest(), response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(adminService.retriedProcessId).isEqualTo(processId);
        assertThat(model.getAttribute("status")).isEqualTo("Retry scheduled");
        assertThat(model.getAttribute("error")).isEqualTo(false);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThatTrigger(response)
            .hasEvent("processDetailsChanged")
            .hasToast("success", "Retry scheduled");
    }

    @Test
    void retryCurrentStepShouldReturnErrorToastWhenRetryFails() {
        var controller = new ProcessesAdminController(
                new RetryAdminService(new IllegalStateException("Process is completed")));
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.retryCurrentStep(UUID.randomUUID(), new MockHttpServletRequest(), response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(model.getAttribute("status")).isEqualTo("Process is completed");
        assertThat(model.getAttribute("error")).isEqualTo(true);
        assertThat(response.getStatus()).isEqualTo(422);
        assertThatTrigger(response)
            .hasToast("error", "Process is completed")
            .doesNotHaveEvent("processDetailsChanged");
    }

    @Test
    void setVariableShouldReturnStatusAndTriggerProcessDetailsRefresh() {
        var processId = UUID.randomUUID();
        var adminService = new VariableAdminService();
        var controller = new ProcessesAdminController(adminService);
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.setVariable(processId, "customer", "{\"name\":\"Ada\"}", new MockHttpServletRequest(),
                response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(adminService.variableProcessId).isEqualTo(processId);
        assertThat(adminService.variableName).isEqualTo("customer");
        assertThat(adminService.variableJsonValue).isEqualTo("{\"name\":\"Ada\"}");
        assertThat(model.getAttribute("status")).isEqualTo("Variable saved");
        assertThat(model.getAttribute("error")).isEqualTo(false);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThatTrigger(response)
            .hasEvent("processDetailsChanged")
            .hasToast("success", "Variable saved");
    }

    @Test
    void setVariableShouldReturnErrorStatusWhenSaveFails() {
        var controller = new ProcessesAdminController(
                new VariableAdminService(new IllegalArgumentException("Invalid JSON")));
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.setVariable(UUID.randomUUID(), "customer", "{bad", new MockHttpServletRequest(),
                response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(model.getAttribute("status")).isEqualTo("Invalid JSON");
        assertThat(model.getAttribute("error")).isEqualTo(true);
        assertThat(response.getStatus()).isEqualTo(422);
        assertThatTrigger(response)
            .hasToast("error", "Invalid JSON")
            .doesNotHaveEvent("processDetailsChanged");
    }

    @Test
    void setVariableShouldEscapeErrorToastAsJson() {
        var controller = new ProcessesAdminController(
                new VariableAdminService(new IllegalArgumentException("Invalid \"JSON\" \\ value\nline")));
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.setVariable(UUID.randomUUID(), "customer", "{bad", new MockHttpServletRequest(),
                response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(response.getHeader("HX-Trigger"))
            .contains("\\\"JSON\\\"")
            .contains("\\\\ value")
            .contains("\\nline");
    }

    @Test
    void removeVariableShouldReturnStatusAndTriggerProcessDetailsRefreshWhenRemoved() {
        var processId = UUID.randomUUID();
        var adminService = new VariableAdminService();
        var controller = new ProcessesAdminController(adminService);
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.removeVariable(processId, "customer", new MockHttpServletRequest(), response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(adminService.removedProcessId).isEqualTo(processId);
        assertThat(adminService.removedName).isEqualTo("customer");
        assertThat(model.getAttribute("status")).isEqualTo("Variable removed");
        assertThat(model.getAttribute("error")).isEqualTo(false);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThatTrigger(response)
            .hasEvent("processDetailsChanged")
            .hasToast("success", "Variable removed");
    }

    @Test
    void removeVariableShouldReturnInfoStatusWhenVariableWasNotFound() {
        var controller = new ProcessesAdminController(new VariableAdminService(false));
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.removeVariable(UUID.randomUUID(), "customer", new MockHttpServletRequest(), response,
                model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(model.getAttribute("status")).isEqualTo("Variable was not found");
        assertThat(model.getAttribute("error")).isEqualTo(false);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThatTrigger(response)
            .hasToast("info", "Variable was not found")
            .doesNotHaveEvent("processDetailsChanged");
    }

    @Test
    void setTimerShouldReadMomentInBrowserTimeZoneAndTriggerProcessDetailsRefresh() {
        var processId = UUID.randomUUID();
        var adminService = new TimerAdminService();
        var controller = new ProcessesAdminController(adminService);
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var viewName = controller.setTimer(processId, "paymentDue", "2026-05-01T12:30:00", "Europe/Berlin",
                new MockHttpServletRequest(), response, model);

        assertThat(viewName).isEqualTo("ruslo-admin/shared/fragments :: statusLine");
        assertThat(adminService.timerProcessId).isEqualTo(processId);
        assertThat(adminService.timerName).isEqualTo("paymentDue");
        assertThat(adminService.timerMoment).isEqualTo("2026-05-01T12:30:00");
        assertThat(adminService.timerZone).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(model.getAttribute("status")).isEqualTo("Timer set");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThatTrigger(response)
            .hasEvent("processDetailsChanged")
            .hasToast("success", "Timer set");
    }

    @Test
    void setTimerShouldReturnErrorStatusForUndeclaredTimer() {
        var controller = new ProcessesAdminController(new TimerAdminService(
                new IllegalArgumentException("Timer oldDue is not used by any at of process ORDER")));
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        controller.setTimer(UUID.randomUUID(), "oldDue", "2026-05-01T12:30:00", null, new MockHttpServletRequest(),
                response, model);

        assertThat(model.getAttribute("error")).isEqualTo(true);
        assertThat(response.getStatus()).isEqualTo(422);
        assertThatTrigger(response)
            .hasToast("error", "Timer oldDue is not used by any at of process ORDER")
            .doesNotHaveEvent("processDetailsChanged");
    }

    @Test
    void cancelTimerShouldTriggerRefreshWhenCancelledAndReportInfoWhenNotSet() {
        var processId = UUID.randomUUID();
        var adminService = new TimerAdminService();
        var controller = new ProcessesAdminController(adminService);
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        controller.cancelTimer(processId, "paymentDue", new MockHttpServletRequest(), response, model);

        assertThat(adminService.cancelledProcessId).isEqualTo(processId);
        assertThat(adminService.cancelledName).isEqualTo("paymentDue");
        assertThat(model.getAttribute("status")).isEqualTo("Timer cancelled");
        assertThatTrigger(response)
            .hasEvent("processDetailsChanged")
            .hasToast("success", "Timer cancelled");

        var notSetResponse = new MockHttpServletResponse();
        var notSetModel = new ConcurrentModel();
        new ProcessesAdminController(new TimerAdminService(false)).cancelTimer(processId, "paymentDue",
                new MockHttpServletRequest(), notSetResponse, notSetModel);

        assertThat(notSetModel.getAttribute("status")).isEqualTo("Timer was not set");
        assertThat(notSetResponse.getStatus()).isEqualTo(200);
        assertThatTrigger(notSetResponse)
            .hasToast("info", "Timer was not set")
            .doesNotHaveEvent("processDetailsChanged");
    }

    private static TriggerAssert assertThatTrigger(MockHttpServletResponse response) {
        return new TriggerAssert(response.getHeader("HX-Trigger"));
    }

    private record TriggerAssert(Map<String, Object> trigger) {

        private TriggerAssert(String header) {
            this(readTrigger(header));
        }

        private TriggerAssert hasEvent(String event) {
            assertThat(trigger).containsEntry(event, "");
            return this;
        }

        private TriggerAssert doesNotHaveEvent(String event) {
            assertThat(trigger).doesNotContainKey(event);
            return this;
        }

        @SuppressWarnings("unchecked")
        private TriggerAssert hasToast(String kind, String text) {
            assertThat(trigger.get("toast")).isInstanceOf(Map.class);
            var toast = (Map<String, Object>) trigger.get("toast");
            assertThat(toast)
                .containsEntry("kind", kind)
                .containsEntry("text", text);
            return this;
        }
    }

    private static Map<String, Object> readTrigger(String header) {
        try {
            return JsonUtils.DEFAULT_OBJECT_MAPPER.readValue(header, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new AssertionError("Invalid HX-Trigger JSON: " + header, ex);
        }
    }

    private static ProcessDetailsResponse processDetails(UUID processId, String state, int attempt) {
        return new ProcessDetailsResponse(
                processId,
                "ORDER",
                state,
                null,
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                Instant.parse("2026-05-01T10:00:00Z"),
                "biz-1",
                "trace-1",
                attempt,
                null,
                List.of(new StateHistoryResponse(state, Instant.parse("2026-05-01T10:00:00Z"), false)),
                Map.of("amount", "42"),
                "STEP", List.of(), null, List.of());
    }

    private static class ProcessDetailsAdminService extends DefinitionsAdminService {
        private final ProcessDetailsResponse process;

        private ProcessDetailsAdminService(ProcessDetailsResponse process) {
            this.process = process;
        }

        @Override
        public Optional<ProcessDetailsResponse> process(UUID processId) {
            return Optional.of(process);
        }
    }

    private static class RetryAdminService extends DefinitionsAdminService {
        private final RuntimeException failure;
        private UUID retriedProcessId;

        private RetryAdminService() {
            this(null);
        }

        private RetryAdminService(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void retryCurrentStep(UUID processId) {
            if (failure != null) {
                throw failure;
            }
            retriedProcessId = processId;
        }
    }

    private static class VariableAdminService extends DefinitionsAdminService {
        private final RuntimeException failure;
        private final boolean removed;
        private UUID variableProcessId;
        private String variableName;
        private String variableJsonValue;
        private UUID removedProcessId;
        private String removedName;

        private VariableAdminService() {
            this(true);
        }

        private VariableAdminService(boolean removed) {
            this(null, removed);
        }

        private VariableAdminService(RuntimeException failure) {
            this(failure, true);
        }

        private VariableAdminService(RuntimeException failure, boolean removed) {
            this.failure = failure;
            this.removed = removed;
        }

        @Override
        public void setVariable(UUID processId, String name, String jsonValue) {
            if (failure != null) {
                throw failure;
            }
            variableProcessId = processId;
            variableName = name;
            variableJsonValue = jsonValue;
        }

        @Override
        public boolean removeVariable(UUID processId, String name) {
            removedProcessId = processId;
            removedName = name;
            return removed;
        }
    }

    private static class TimerAdminService extends DefinitionsAdminService {
        private final RuntimeException failure;
        private final boolean cancelled;
        private UUID timerProcessId;
        private String timerName;
        private String timerMoment;
        private ZoneId timerZone;
        private UUID cancelledProcessId;
        private String cancelledName;

        private TimerAdminService() {
            this(null, true);
        }

        private TimerAdminService(boolean cancelled) {
            this(null, cancelled);
        }

        private TimerAdminService(RuntimeException failure) {
            this(failure, true);
        }

        private TimerAdminService(RuntimeException failure, boolean cancelled) {
            this.failure = failure;
            this.cancelled = cancelled;
        }

        @Override
        public void setTimer(UUID processId, String name, String moment, ZoneId zone) {
            if (failure != null) {
                throw failure;
            }
            timerProcessId = processId;
            timerName = name;
            timerMoment = moment;
            timerZone = zone;
        }

        @Override
        public boolean cancelTimer(UUID processId, String name) {
            cancelledProcessId = processId;
            cancelledName = name;
            return cancelled;
        }
    }

    private static class DashboardAdminService extends DefinitionsAdminService {
        private static final List<DashboardResponse.InstanceResponse> INSTANCES = List.of(
                new DashboardResponse.InstanceResponse(UUID.fromString("01a1280a-0e00-7000-8000-ec6504b28789"), 2, 25,
                        true),
                new DashboardResponse.InstanceResponse(UUID.fromString("01a1280a-0e00-7000-8000-000000000002"), 0, -3,
                        false));

        private final List<DashboardResponse.InstanceResponse> instances;
        private ZoneId zone;

        private DashboardAdminService() {
            this(INSTANCES);
        }

        private DashboardAdminService(List<DashboardResponse.InstanceResponse> instances) {
            this.instances = instances;
        }

        @Override
        public DashboardResponse dashboard(ZoneId zone) {
            this.zone = zone;
            return new DashboardResponse(3, 4, 0, "2026-10-11T10:15", zone.getId(),
                    Map.of("TICKET", new DashboardResponse.TypeCounts(3, 0, 0),
                            "ORDER", new DashboardResponse.TypeCounts(0, 4, 0)),
                    instances);
        }
    }

    private static class DefinitionsAdminService extends ProcessEngineAdminService {
        private DefinitionsAdminService() {
            super(null, null);
        }

        @Override
        public List<DefinitionResponse> definitions() {
            return List.of(
                    new DefinitionResponse(
                            "ORDER",
                            "Order",
                            "CREATED",
                            List.of(
                                    new DefinitionResponse.StateResponse("CREATED", null, "STEP", "default", List.of(),
                                            null),
                                    new DefinitionResponse.StateResponse("PAID", null, "STEP", "default", List.of(),
                                            null),
                                    new DefinitionResponse.StateResponse("CANCELLED", null, "STEP", "default",
                                            List.of(), null))),
                    new DefinitionResponse(
                            "TICKET",
                            "Ticket",
                            "WAITING",
                            List.of(
                                    new DefinitionResponse.StateResponse("WAITING", null, "STEP", "default", List.of(),
                                            null),
                                    new DefinitionResponse.StateResponse("CANCELLED", null, "STEP", "default",
                                            List.of(), null))));
        }
    }

    private static SpringTemplateEngine templateEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCacheable(false);
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
