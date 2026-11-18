package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import io.github.neherim.ruslo.admin.view.Breadcrumb;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessEngineAdminUiPageControllersTest {

    @Test
    void dashboardPageShouldExposeBreadcrumbs() {
        var controller = new DashboardAdminController(new CountingAdminService(null, List.of()));
        var model = new ConcurrentModel();

        var viewName = controller.dashboard(model);

        assertThat(viewName).isEqualTo("ruslo-admin/dashboard/page");
        assertThat(model.getAttribute("breadcrumbs")).isEqualTo(List.of(Breadcrumb.current("Dashboard")));
    }

    @Test
    void processesPageShouldExposeBreadcrumbs() {
        var definitions = List.of(new DefinitionResponse(
                "ADMIN_TEST",
                "Admin test",
                "WAIT",
                List.of(new DefinitionResponse.StateResponse("WAIT", null, "STEP", "default", List.of(), null))));
        var controller = new ProcessesAdminController(new CountingAdminService(null, definitions));
        var model = new ConcurrentModel();

        var viewName = controller.processes(new ProcessSearchRequest(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null), new MockHttpServletRequest(), model);

        assertThat(viewName).isEqualTo("ruslo-admin/processes/page");
        assertThat(model.getAttribute("breadcrumbs")).isEqualTo(List.of(Breadcrumb.current("Processes")));
        assertThat(model.getAttribute("definitions")).isEqualTo(definitions);
        assertThat(model.getAttribute("states")).isEqualTo(List.of("WAIT"));
        assertThat(model.getAttribute("page")).isEqualTo(new ProcessPageResponse(List.of(), false, 50, 0));
        assertThat(model.getAttribute("returnTo")).isEqualTo("processes");
    }

    @Test
    void processesPageShouldPassStateOutsideSelectedTypeOptionsToSearch() {
        var adminService = new CountingAdminService(
                null,
                List.of(new DefinitionResponse(
                        "ORDER",
                        "Order",
                        "CREATED",
                        List.of(new DefinitionResponse.StateResponse("CREATED", null, "STEP", "default", List.of(),
                                null)))));
        var controller = new ProcessesAdminController(adminService);
        var model = new ConcurrentModel();

        controller.processes(new ProcessSearchRequest(
                null, "ORDER", "WAITING", null, null, null,
                null, null, null, null, null, null, null, null), new MockHttpServletRequest(), model);

        assertThat(adminService.searchRequest.stateName()).isEqualTo("WAITING");
        assertThat(((ProcessSearchRequest) model.getAttribute("searchForm")).stateName()).isEqualTo("WAITING");
        assertThat(model.getAttribute("states")).isEqualTo(List.of("CREATED"));
    }

    @Test
    void processesPageShouldRenderFilteredQueryAsFullPage() {
        var definitions = List.of(new DefinitionResponse(
                "ORDER",
                "Order",
                "CREATED",
                List.of(new DefinitionResponse.StateResponse("CREATED", null, "STEP", "default", List.of(), null))));
        var adminService = new CountingAdminService(null, definitions);
        var controller = new ProcessesAdminController(adminService);
        var model = new ConcurrentModel();
        var searchForm = new ProcessSearchRequest(
                null,
                "ORDER",
                "CREATED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                25,
                50,
                null);
        var request = new MockHttpServletRequest();
        request.setQueryString("typeName=ORDER&stateName=CREATED&limit=25&offset=50");

        var viewName = controller.processes(searchForm, request, model);

        assertThat(viewName).isEqualTo("ruslo-admin/processes/page");
        assertThat(model.getAttribute("searchForm")).isEqualTo(searchForm);
        assertThat(model.getAttribute("page")).isEqualTo(new ProcessPageResponse(List.of(), false, 25, 50));
        assertThat(model.getAttribute("returnTo"))
            .isEqualTo("processes?typeName=ORDER&stateName=CREATED&limit=25&offset=50");
        assertThat(adminService.searchRequest.typeName()).isEqualTo("ORDER");
        assertThat(adminService.searchRequest.stateName()).isEqualTo("CREATED");
        assertThat(adminService.searchLimit).isEqualTo(25);
        assertThat(adminService.searchOffset).isEqualTo(50);
    }

    @Test
    void processesPageShouldMapStatusFilter() {
        var adminService = new CountingAdminService(null, List.of());
        var controller = new ProcessesAdminController(adminService);
        var model = new ConcurrentModel();

        controller.processes(new ProcessSearchRequest(
                null, null, null, io.github.neherim.ruslo.core.api.view.ProcessStatus.FAILED, null, null,
                null, null, null, null, null, null, null, null), new MockHttpServletRequest(), model);

        assertThat(adminService.searchRequest.status())
            .isEqualTo(io.github.neherim.ruslo.core.api.view.ProcessStatus.FAILED);
    }

    @Test
    void shouldLoadProcessOnceWhenOpeningProcessDetailsPage() {
        var processId = UUID.randomUUID();
        var process = new ProcessDetailsResponse(
                processId,
                "ADMIN_TEST",
                "WAIT",
                null,
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                null,
                "biz-1",
                "trace-1",
                0,
                null,
                List.of(
                        new StateHistoryResponse("WAIT", Instant.parse("2026-05-01T10:00:00Z"), false),
                        new StateHistoryResponse("NEXT", Instant.parse("2026-05-01T10:01:00Z"), false)),
                Map.of(
                        "amount", "42",
                        "customer", "{\"name\":\"Ada\"}"),
                "AWAIT", List.of("payment", "shipping"), null, List.of());
        var adminService = new CountingAdminService(
                process,
                List.of(new DefinitionResponse(
                        "ADMIN_TEST",
                        "Admin test",
                        "WAIT",
                        List.of(
                                new DefinitionResponse.StateResponse("WAIT", null, "STEP", "default", List.of(), null),
                                new DefinitionResponse.StateResponse("NEXT", null, "STEP", "default", List.of(),
                                        null)))));
        var controller = new ProcessesAdminController(adminService);
        var model = new ConcurrentModel();

        var viewName = controller.processDetails(processId, null, model);

        assertThat(viewName).isEqualTo("ruslo-admin/process-details/page");
        assertThat(model.getAttribute("process")).isSameAs(process);
        assertThat(model.getAttribute("states")).isEqualTo(List.of(
                new DefinitionResponse.StateResponse("WAIT", null, "STEP", "default", List.of(), null),
                new DefinitionResponse.StateResponse("NEXT", null, "STEP", "default", List.of(), null)));
        assertThat(process.variablesCount()).isEqualTo(2);
        assertThat(process.acceptedMessageTypes()).containsExactly("payment", "shipping");
        assertThat(process.historyCount()).isEqualTo(2);
        assertThat(model.getAttribute("breadcrumbs")).isEqualTo(List.of(
                Breadcrumb.link("Processes", "./processes"),
                Breadcrumb.current(processId.toString())));
        assertThat(adminService.processCalls).isEqualTo(1);
    }

    @Test
    void processDetailsPageShouldUseValidReturnToForProcessesBreadcrumb() {
        var processId = UUID.randomUUID();
        var process = processDetails(processId);
        var adminService = new CountingAdminService(
                process,
                List.of(new DefinitionResponse(
                        "ADMIN_TEST",
                        "Admin test",
                        "WAIT",
                        List.of(new DefinitionResponse.StateResponse("WAIT", null, "STEP", "default", List.of(),
                                null)))));
        var controller = new ProcessesAdminController(adminService);
        var model = new ConcurrentModel();

        var viewName = controller.processDetails(
                processId,
                "processes?typeName=ORDER&stateName=CREATED&limit=25&offset=50",
                model);

        assertThat(viewName).isEqualTo("ruslo-admin/process-details/page");
        assertThat(model.getAttribute("breadcrumbs")).isEqualTo(List.of(
                Breadcrumb.link("Processes", "./processes?typeName=ORDER&stateName=CREATED&limit=25&offset=50"),
                Breadcrumb.current(processId.toString())));
    }

    @Test
    void processDetailsPageShouldAllowSpecialCharactersInsideReturnToQuery() {
        var processId = UUID.randomUUID();
        var returnTo = "processes?businessKey=https://example.com/a..b&limit=50&offset=0";
        var controller = new ProcessesAdminController(
                new CountingAdminService(processDetails(processId), List.of()));
        var model = new ConcurrentModel();

        controller.processDetails(processId, returnTo, model);

        assertThat(model.getAttribute("breadcrumbs")).isEqualTo(List.of(
                Breadcrumb.link("Processes", "./" + returnTo),
                Breadcrumb.current(processId.toString())));
    }

    @Test
    void processDetailsPageShouldIgnoreUnsafeReturnTo() {
        var processId = UUID.randomUUID();
        var unsafeReturnToValues = List.of(
                "https://example.com",
                "//example.com",
                "/ruslo/processes",
                "../processes",
                "processes/../dashboard",
                "api/processes",
                "htmx/processes");

        for (var unsafeReturnTo : unsafeReturnToValues) {
            var controller = new ProcessesAdminController(
                    new CountingAdminService(processDetails(processId), List.of()));
            var model = new ConcurrentModel();

            controller.processDetails(processId, unsafeReturnTo, model);

            assertThat(model.getAttribute("breadcrumbs"))
                .as("returnTo=%s", unsafeReturnTo)
                .isEqualTo(List.of(
                        Breadcrumb.link("Processes", "./processes"),
                        Breadcrumb.current(processId.toString())));
        }
    }

    @Test
    void processDetailsTabsShouldExposeOnlyVariablesTimersAndHistoryWithCounts() {
        var context = new Context();
        context.setVariable("process", processDetails(UUID.randomUUID()));
        context.setVariable("states",
                List.of(new DefinitionResponse.StateResponse("WAIT", null, "STEP", "default", List.of(), null)));

        var html = templateEngine().process("ruslo-admin/process-details/fragments", Set.of("processDetailsContent"),
                context);

        assertThat(html)
            .contains("Variables (1)")
            .contains("Timers (0)")
            .contains("History (0)")
            .doesNotContain("Metadata");
    }

    @Test
    void processDetailsShouldRenderStatusDescriptionCopyButtonsAndRelativeSchedule() {
        var processId = UUID.randomUUID();
        var process = new ProcessDetailsResponse(
                processId,
                "ADMIN_TEST",
                "WAIT",
                "Waiting for payment",
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                Instant.parse("2026-05-01T10:05:00Z"),
                "biz-1",
                "trace-1",
                2,
                null,
                List.of(
                        new StateHistoryResponse("START", Instant.parse("2026-05-01T10:00:00Z"), false),
                        new StateHistoryResponse("WAIT", Instant.parse("2026-05-01T10:01:00Z"), true)),
                Map.of("customer", "{\"name\":\"Ada\"}", "amount", "42"),
                "AWAIT", List.of("payment"), "every 10m · after 1h · at dueDate", List.of());

        var html = renderDetails(process);

        assertThat(html)
            .contains("class=\"badge status-active\"")
            .contains(">Waiting for payment</small>")
            .contains(">every 10m · after 1h · at dueDate</small>")
            .contains("data-copy=\"" + processId + "\"")
            .contains("data-copy=\"biz-1\"")
            .contains("data-copy=\"trace-1\"")
            .contains("class=\"relative-time\"")
            .contains("data-overdue-check=\"true\"")
            .contains("hx-get=\"htmx/processes/" + processId + "/details?since=" + process.version() + "\"")
            .contains("hx-trigger=\"every 5s [AdminUi.canRefresh()]\"")
            .containsPattern(
                    "<button type=\"button\"\\s+class=\"actions-menu-item\"\\s+data-modal-open=\"send-message-modal\">")
            .contains("retry-current-step\" disabled=\"disabled\">Retry current step")
            .contains("Only for FAILED processes")
            .doesNotContain("Current state accepts no messages");
        assertThat(html)
            .contains("<details class=\"variable-value\">")
            .contains("class=\"mono variable-json\"")
            .contains("<pre class=\"mono variable-value\">42</pre>")
            .contains("data-copy=\"{&quot;name&quot;:&quot;Ada&quot;}\"");
        assertThat(html)
            .contains("class=\"history-current\"")
            .contains(">current</span>")
            .contains(">Manual move</span>");
    }

    @Test
    void processDetailsTimersShouldShowMomentAwaitedMarkAndDisableUnavailableActions() {
        var processId = UUID.randomUUID();
        var process = new ProcessDetailsResponse(
                processId,
                "ADMIN_TEST",
                "WAIT",
                null,
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                null,
                null,
                "trace-1",
                0,
                null,
                List.of(),
                Map.of(),
                "AWAIT", List.of(), "at paymentDue", List.of(
                        new ProcessDetailsResponse.TimerResponse("oldDue", Instant.parse("2026-05-01T09:00:00Z"),
                                false, false),
                        new ProcessDetailsResponse.TimerResponse("paymentDue", Instant.parse("2026-05-01T10:15:00Z"),
                                true, true),
                        new ProcessDetailsResponse.TimerResponse("reminder", null, false, true)));

        var html = renderDetails(process);

        assertThat(html)
            .contains("Timers (3)")
            .contains("id=\"set-timer-modal\"")
            .contains("hx-post=\"htmx/processes/" + processId + "/timers\"")
            .contains("hx-vals='js:{timeZone: AdminUi.browserTimeZone()}'")
            .contains("data-timer-name=\"paymentDue\" data-timer-moment=\"2026-05-01T10:15:00Z\"")
            .contains("datetime=\"2026-05-01T10:15:00Z\"")
            .contains(">awaited</span>")
            .contains(">not awaited</span>")
            .contains(">not set</span>")
            .contains("title=\"Set: not declared by any at of the current definition\"")
            .contains("title=\"Cancel: not set\"")
            .contains("hx-delete=\"htmx/processes/" + processId + "/timers?name=paymentDue\"");
    }

    @Test
    void completedProcessDetailsShouldNotPollAndShouldDisableUnavailableActions() {
        var process = new ProcessDetailsResponse(
                UUID.randomUUID(),
                "ADMIN_TEST",
                "DONE",
                null,
                io.github.neherim.ruslo.core.api.view.ProcessStatus.COMPLETED,
                Instant.parse("2026-05-01T10:05:00Z"),
                null,
                "trace-1",
                0,
                null,
                List.of(new StateHistoryResponse("DONE", Instant.parse("2026-05-01T10:00:00Z"), false)),
                Map.of(),
                "TERMINAL", List.of(), null, List.of());

        var html = renderDetails(process);

        assertThat(html)
            .contains("class=\"badge status-completed\"")
            .doesNotContain("details-poll")
            .doesNotContain("data-overdue-check")
            .doesNotContain("history-current")
            .doesNotContain("data-from=")
            .contains("title=\"Copy business key\" disabled=\"disabled\">")
            .contains("Retry current step")
            .contains("Only for FAILED processes")
            .contains("Current state accepts no messages")
            .doesNotContain("id=\"send-message-modal\"");
    }

    @Test
    void processesFragmentShouldRenderClickableRowWithShortCopyableId() {
        var processId = UUID.fromString("01a1280a-0e2f-7220-9384-ec6504b28789");
        var context = new Context();
        context.setVariable("processes", List.of(new ProcessSummaryResponse(
                processId,
                "ORDER",
                "CREATED",
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                Instant.parse("2026-05-03T11:00:00Z"),
                Instant.parse("2026-05-03T12:00:00Z"),
                "biz-1")));
        context.setVariable("page", new ProcessPageResponse(List.of(), false, 25, 50));
        context.setVariable("status", "Loaded 1 processes");
        context.setVariable("returnTo", "processes?typeName=ORDER&stateName=CREATED&limit=25&offset=50");

        var html = templateEngine().process("ruslo-admin/processes/fragments", Set.of("processResults"), context);

        assertThat(html)
            .contains("<tr class=\"process-row\">")
            .contains("href=\"processes/" + processId
                    + "?returnTo=processes?typeName%3DORDER%26stateName%3DCREATED%26limit%3D25%26offset%3D50\"")
            .doesNotContain("href=\"#ti-eye\"")
            .doesNotContain("data-href");
        assertThat(html)
            .contains("title=\"" + processId + "\">…ec6504b28789</a>")
            .contains("data-copy=\"" + processId + "\"")
            .contains("aria-label=\"Copy process ID\"")
            .contains("href=\"#ti-copy\"")
            .contains("class=\"local-date-time\"")
            .contains("2026-05-03T11:00:00Z")
            .contains("<span class=\"badge status-active\">ACTIVE</span>")
            .doesNotContain("status-dot")
            .doesNotContain("colspan=\"8\"");
    }

    @Test
    void processesFragmentShouldRenderEmptyStateAcrossActionColumn() {
        var context = new Context();
        context.setVariable("processes", List.of());
        context.setVariable("page", new ProcessPageResponse(List.of(), false, 25, 0));
        context.setVariable("status", "No processes");
        context.setVariable("returnTo", "processes");

        var html = templateEngine().process("ruslo-admin/processes/fragments", Set.of("processResults"), context);

        assertThat(html)
            .contains("colspan=\"7\"")
            .contains("No processes")
            .doesNotContain("colspan=\"8\"");
    }

    private static class CountingAdminService extends ProcessEngineAdminService {
        private final ProcessDetailsResponse process;
        private final List<DefinitionResponse> definitions;
        private ProcessSearchRequest searchRequest;
        private int searchLimit;
        private int searchOffset;
        private int processCalls;

        private CountingAdminService(ProcessDetailsResponse process, List<DefinitionResponse> definitions) {
            super(null, null);
            this.process = process;
            this.definitions = definitions;
        }

        @Override
        public Optional<ProcessDetailsResponse> process(UUID processId) {
            processCalls++;
            return Optional.of(process);
        }

        @Override
        public List<DefinitionResponse> definitions() {
            return definitions;
        }

        @Override
        public ProcessPageResponse searchProcesses(ProcessSearchRequest request) {
            searchRequest = request;
            searchLimit = request.pageLimit();
            searchOffset = request.pageOffset();
            return new ProcessPageResponse(List.of(), false, searchLimit, searchOffset);
        }

    }

    private static ProcessDetailsResponse processDetails(UUID processId) {
        return new ProcessDetailsResponse(
                processId,
                "ADMIN_TEST",
                "WAIT",
                null,
                io.github.neherim.ruslo.core.api.view.ProcessStatus.ACTIVE,
                null,
                "biz-1",
                "trace-1",
                0,
                null,
                List.of(),
                Map.of("amount", "42"),
                "AWAIT", List.of(), null, List.of());
    }

    private static String renderDetails(ProcessDetailsResponse process) {
        var context = new Context();
        context.setVariable("process", process);
        context.setVariable("states", List.of(new DefinitionResponse.StateResponse(
                process.state(), null, process.stateKind(), "default", List.of(), null)));
        return templateEngine().process("ruslo-admin/process-details/fragments", Set.of("processDetailsContent"),
                context);
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
