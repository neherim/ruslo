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

import java.nio.file.Files;
import java.nio.file.Path;
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
                "AWAIT", List.of("payment", "shipping"), null);
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
    void processDetailsPageShouldNotTriggerInitialHtmxLoads() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/page.html"));

        assertThat(page).doesNotContain("hx-trigger=\"load");
        assertThat(page).contains("processDetailsChanged from:body");
        assertThat(page).contains("htmx/processes/${processId}/details");
    }

    @Test
    void processDetailsPageShouldNotReferencePartialProcessHtmxEndpoints() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/page.html"));
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));

        assertThat(page + fragments)
            .doesNotContain("htmx/processes/${processId}/hero")
            .doesNotContain("htmx/processes/${processId}/metadata")
            .doesNotContain("htmx/processes/${processId}/change-state-form")
            .doesNotContain("htmx/processes/${processId}/history")
            .doesNotContain("htmx/processes/${processId}/bpmn")
            .doesNotContain("variables/${name}/edit")
            .doesNotContain("variableEditRow")
            .doesNotContain("emptyVariableRow");
    }

    @Test
    void processDetailsTabsShouldExposeOnlyVariablesAndHistoryWithCounts() throws Exception {
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var context = new Context();
        context.setVariable("process", processDetails(UUID.randomUUID()));
        context.setVariable("states",
                List.of(new DefinitionResponse.StateResponse("WAIT", null, "STEP", "default", List.of(), null)));

        var html = templateEngine().process("ruslo-admin/process-details/fragments", Set.of("processDetailsContent"),
                context);

        assertThat(fragments)
            .contains("th:text=\"|Variables (${process.variablesCount()})|\"")
            .contains("th:text=\"|History (${process.historyCount()})|\"")
            .doesNotContain("tab-messages")
            .doesNotContain("tab-meta")
            .doesNotContain("processMessages")
            .doesNotContain("processMetadata");
        assertThat(html)
            .contains("Variables (1)")
            .contains("History (0)")
            .doesNotContain("Metadata");
    }

    @Test
    void processDetailsHeroShouldExposeActionsMenu() throws Exception {
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var actionsMenu = fragments.substring(
                fragments.indexOf("<details class=\"actions-menu\">"),
                fragments.indexOf("<th:block th:fragment=\"changeStateForm\">"));
        var hero = fragments.substring(
                fragments.indexOf("<div th:fragment=\"processHero\""),
                fragments.indexOf("<th:block th:fragment=\"changeStateForm\">"));

        assertThat(fragments)
            .contains("<span>Process ID</span>")
            .contains("class=\"hero-title mono\" th:text=\"${process.id}\"")
            .contains("statusBadge(${process.status.name()})")
            .contains("class=\"hero-fields\"")
            .contains("<span>State</span>")
            .contains("th:text=\"${process.stateDescription}\"")
            .contains("<span>Type</span>")
            .contains("<span>State kind</span>")
            .contains("<span>Attempt</span>")
            .contains("<span>Business key</span>")
            .contains("th:text=\"${process.businessKey ?: '—'}\"")
            .contains("<span>Correlation id</span>")
            .contains("<span>Next run</span>")
            .contains("copyButton(${process.id}, 'Copy process ID')")
            .contains("copyButton(${process.businessKey}, 'Copy business key')")
            .contains("copyButton(${process.correlationId}, 'Copy correlation id')")
            .contains("class=\"actions-menu\"")
            .contains(">Actions</summary>")
            .contains("class=\"actions-menu-item\"")
            .contains("data-modal-open=\"change-state-drawer\"")
            .contains(">Change state</button>")
            .contains("hx-post=@{|htmx/processes/${process.id}/retry-current-step|}")
            .contains("hx-target=\"#process-action-status\"")
            .contains("th:disabled=\"${process.status.name() != 'FAILED'}\"")
            .contains("hx-confirm=\"Retry the current process step?\"")
            .contains(">Retry current step\n")
            .contains("id=\"process-action-status\"");
        assertThat(hero)
            .doesNotContain("class=\"hero-chips\"")
            .doesNotContain("class=\"hero-detail");
        assertThat(fragments)
            .doesNotContain("class=\"hero-row hero-row-id\"")
            .doesNotContain("${process.businessKey != null ? process.businessKey : process.id}");
        assertThat(actionsMenu)
            .doesNotContain(">Retry<")
            .doesNotContain(">Cancel<")
            .doesNotContain(">Terminate<");
    }

    @Test
    void processDetailsVariablesShouldUseModalForAddAndEdit() throws Exception {
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var adminJs = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.js"));
        var variableDialog = fragments.substring(
                fragments.indexOf("<dialog id=\"add-variable-modal\""),
                fragments.indexOf("<dialog id=\"send-message-modal\""));
        var actionBar = variableDialog.substring(
                variableDialog.indexOf("<div class=\"action-bar\">"),
                variableDialog.indexOf("</div>", variableDialog.indexOf("<div class=\"action-bar\">")));

        assertThat(variableDialog)
            .contains("class=\"variable-add-form\"")
            .contains("hx-target=\"#add-variable-status\"")
            .contains("id=\"add-variable-status\"")
            .contains("id=\"variable-modal-title\"")
            .contains("id=\"variable-submit-button\"")
            .contains("<label>Value <textarea class=\"json-input\" name=\"jsonValue\"")
            .contains("data-modal-close")
            .contains("<button type=\"submit\" id=\"variable-submit-button\">Add</button>")
            .contains("<button type=\"button\" class=\"secondary\" data-modal-close>Cancel</button>")
            .doesNotContain("JSON value")
            .doesNotContain("style=\"margin-top: 10px;\"");
        assertThat(actionBar.indexOf(">Add</button>"))
            .isLessThan(actionBar.indexOf(">Cancel</button>"));
        assertThat(fragments)
            .contains("data-variable-add")
            .contains("data-variable-edit")
            .contains("data-variable-name=${entry.key},data-variable-value=${entry.value}")
            .contains("hx-delete=@{htmx/processes/{id}/variables(id=${process.id},name=${entry.key})}")
            .contains("hx-target=\"#variables-status\"")
            .contains("hx-confirm=\"Remove this variable?\"")
            .doesNotContain("variable-edit-form")
            .doesNotContain("hx-get=@{|htmx/processes/${processId}/variables/${name}/edit|}")
            .doesNotContain("th:fragment=\"variableRow");
        assertThat(adminJs)
            .contains("const VariableForms")
            .contains("configureModal('Add variable', 'Add', '', '{}')")
            .contains("configureModal(\n                'Edit variable',")
            .contains("row.dataset.variableName")
            .contains("row.dataset.variableValue")
            .contains("VariableForms.handleClick");
    }

    @Test
    void processDetailsMessagesShouldOpenDialogFromActionsMenuOnly() throws Exception {
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var actionsMenu = fragments.substring(
                fragments.indexOf("<details class=\"actions-menu\">"),
                fragments.indexOf("</details>", fragments.indexOf("<details class=\"actions-menu\">")));
        var messageDialog = fragments.substring(
                fragments.indexOf("<dialog id=\"send-message-modal\""),
                fragments.indexOf("<dialog class=\"small-modal\" id=\"change-state-drawer\""));

        assertThat(fragments.split("data-modal-open=\"send-message-modal\"", -1)).hasSize(2);
        assertThat(actionsMenu)
            .contains("th:disabled=\"${!process.acceptsMessages()}\"")
            .contains("data-modal-open=\"send-message-modal\">Send message")
            .doesNotContainPattern("<button[^>]*th:if=");
        assertThat(fragments).doesNotContain("hero-actions");
        assertThat(messageDialog)
            .contains("th:if=\"${process.acceptsMessages()}\"")
            .contains("class=\"small-modal message-modal\"")
            .contains("class=\"message-send\"")
            .contains("hx-target=\"#message-status\"")
            .contains("id=\"message-status\"")
            .contains("data-modal-close")
            .contains(">Send async message</button>");
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
                "AWAIT", List.of("payment"), "PT5M");

        var html = renderDetails(process);

        assertThat(html)
            .contains("class=\"badge status-active\"")
            .contains(">Waiting for payment</small>")
            .contains("Timer PT5M")
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
                "TERMINAL", List.of(), null);

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
    void confirmationsShouldUseStyledDialogInsteadOfBrowserConfirm() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/page.html"));
        var sharedFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/shared/fragments.html"));
        var adminJs = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.js"));

        assertThat(page).contains("shared/fragments :: confirmDialog");
        assertThat(sharedFragments)
            .contains("id=\"confirm-dialog\" th:fragment=\"confirmDialog\"")
            .contains("id=\"confirm-dialog-question\"")
            .contains("data-confirm-accept");
        assertThat(adminJs)
            .contains("addEventListener('htmx:confirm', Confirmations.handleConfirm)")
            .contains("request.issueRequest(true)")
            .contains("window.AdminUi = AdminUi;");
    }

    @Test
    void statePillsShouldNotUseDynamicHueColoring() throws Exception {
        var processDetailsFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var processesFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/fragments.html"));
        var adminCss = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.css"));

        assertThat(processDetailsFragments + processesFragments + adminCss)
            .doesNotContain("stateColors")
            .doesNotContain("--state-hue")
            .doesNotContain("--state-l")
            .doesNotContain("oklch(var(--state");
        assertThat(adminCss)
            .contains("background: var(--neutral-soft)")
            .contains("border: 1px solid var(--border)");
    }

    @Test
    void actionsMenuShouldCloseOnOutsideClick() throws Exception {
        var adminJs = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.js"));

        assertThat(adminJs)
            .contains("const ActionMenus")
            .contains("closest(event, '.actions-menu')")
            .contains("document.querySelectorAll('.actions-menu[open]')")
            .contains("ActionMenus.handleClick,\n        Theme.handleClick");
    }

    @Test
    void actionsMenuShouldRenderAboveSiblingPanels() throws Exception {
        var adminCss = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.css"));

        assertThat(adminCss)
            .contains("""
                    .hero-panel {
                        position: relative;
                        z-index: 2;
                        padding: 0;
                        overflow: visible;
                    }
                    """)
            .contains("z-index: 20;");
    }

    @Test
    void diagramViewsShouldExposeNavigationControls() throws Exception {
        var dashboardPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/dashboard/page.html"));
        var processDetailsFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var adminJs = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.js"));
        var adminCss = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.css"));

        assertThat(dashboardPage)
            .contains("id=\"diagram-dialog\"")
            .contains("class=\"diagram-toolbar\"")
            .contains("data-diagram-control=\"zoom-in\"")
            .contains("data-diagram-control=\"zoom-out\"")
            .contains("data-diagram-control=\"fit\"")
            .contains("diagramLegend(false)")
            .doesNotContain("bpmn");
        assertThat(processDetailsFragments)
            .contains("class=\"diagram-toolbar\"")
            .contains("data-diagram-control=\"zoom-in\"")
            .contains("data-diagram-control=\"zoom-out\"")
            .contains("data-diagram-control=\"fit\"")
            .contains("data-diagram-control=\"fullscreen\"")
            .contains("diagramLegend(true)")
            .contains("<div id=\"diagram-canvas\" hx-preserve=\"true\"")
            .contains("data-current=${process.state}")
            .contains("data-status=${process.status}")
            .contains("data-history=${#strings.listJoin(process.stateHistory.![state], ',')}")
            .contains("data-history-at=${#strings.listJoin(process.stateHistory.![changedAt], ',')}")
            .doesNotContain("bpmn");
        assertThat(adminJs)
            .contains("assets/vendor/cytoscape/3.34.3/cytoscape.min.js")
            .contains("assets/vendor/cytoscape-dagre/4.0.1/cytoscape-dagre.min.js")
            .contains("api/definitions/${encodeURIComponent(type)}/graph")
            .doesNotContain("bpmn");
        assertThat(adminCss)
            .contains(".diagram-section .diagram-canvas-shell #diagram-canvas")
            .contains("height: clamp(360px, 52vh, 620px)")
            .doesNotContain("bpmn")
            .doesNotContain("diagram-editor");
        assertThat(Path.of("src/main/resources/ruslo-admin/assets/vendor/cytoscape/3.34.3/cytoscape.min.js"))
            .exists();
        assertThat(Path.of("src/main/resources/ruslo-admin/assets/vendor/cytoscape-dagre/4.0.1/cytoscape-dagre.min.js"))
            .exists();
    }

    @Test
    void processesPageShouldExposeProcessIdAndAdvancedCompletedFilters() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/page.html"));
        var adminCss = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.css"));

        assertThat(page).contains("name=\"processId\"");
        assertThat(page).contains("name=\"createdAtFrom\"");
        assertThat(page).contains("name=\"createdAtTo\"");
        assertThat(page).doesNotContain("name=\"attempted\"");
        assertThat(page).contains("<label class=\"uuid-filter\">Id");
        assertThat(page).contains("<label class=\"uuid-filter\">Correlation id");
        assertThat(page).contains("hx-get=\"processes\"");
        assertThat(page).contains("hx-select=\"#process-results\"");
        assertThat(page).contains("hx-target=\"#process-results\"");
        assertThat(page).contains("hx-swap=\"outerHTML\"");
        assertThat(page).contains("hx-push-url=\"true\"");
        assertThat(page).containsPattern(
                "(?s)<div class=\"field-grid primary-filters\">.*name=\"stateName\".*name=\"status\".*name=\"businessKey\".*<details class=\"advanced\">");
        assertThat(page).doesNotContainPattern("(?s)<details class=\"advanced\">.*name=\"status\".*</details>");
        assertThat(page).containsPattern(
                "(?s)<details class=\"advanced\">.*Created from.*name=\"createdAtFrom\".*Created to.*name=\"createdAtTo\".*</details>");
        assertThat(page).containsPattern("(?s)name=\"businessKey\".*<div class=\"action-bar\">");
        assertThat(adminCss).contains(".field-grid .uuid-filter");
    }

    @Test
    void processesPageShouldRenderInitialFiltersAndResultsWithoutLoadFragments() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/page.html"));

        assertThat(page)
            .doesNotContain("filter-options")
            .doesNotContain("hx-trigger=\"load")
            .contains("th:each=\"definition : ${definitions}\"")
            .contains("th:each=\"state : ${states}\"")
            .contains("htmx/definitions/state-options")
            .containsPattern(
                    "(?s)<select name=\"typeName\"[^>]*hx-select=\"#state-filter\"[^>]*hx-target=\"#state-filter\"")
            .contains("hx-trigger=\"submit\"")
            .doesNotContain("processListChanged from:body, refresh")
            .doesNotContain("change delay:300ms from:select")
            .doesNotContain("keyup changed delay:400ms from:input")
            .doesNotContainPattern("(?s)<div id=\"process-results\"[^>]*hx-get=")
            .contains("ruslo-admin/processes/fragments :: processResults");
    }

    @Test
    void processesPaginationShouldSearchOnlyFromExplicitControls() throws Exception {
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/fragments.html"));

        assertThat(fragments)
            .doesNotContain("hx-get=\"htmx/processes\"")
            .doesNotContain("row-link")
            .doesNotContain("data-href")
            .contains("th:href=\"@{|processes/${process.id}|(returnTo=${returnTo})}\"")
            .containsPattern(
                    "(?s)<button[^>]*hx-get=\"processes\"[^>]*hx-select=\"#process-results\"[^>]*data-page-action=\"prev\"")
            .containsPattern(
                    "(?s)<button[^>]*hx-get=\"processes\"[^>]*hx-select=\"#process-results\"[^>]*data-page-action=\"next\"")
            .containsPattern(
                    "(?s)<select data-page-size[^>]*hx-get=\"processes\"[^>]*hx-trigger=\"change\"[^>]*hx-select=\"#process-results\"");
        assertThat(fragments)
            .contains("<th class=\"col-status\">Status</th>")
            .doesNotContain("col-attempt");
    }

    @Test
    void processesPageShouldKeepCompactFiltersAndDataPanelResultsStyling() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/page.html"));
        var adminCss = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.css"));

        assertThat(page)
            .contains("<article class=\"filter-bar\">")
            .contains("<article class=\"results-panel\">")
            .contains("<details class=\"advanced\">")
            .contains("<div id=\"process-results\">");
        assertThat(adminCss)
            .contains("details.advanced > summary")
            .contains("display: inline-flex")
            .contains("details.advanced[open] > summary")
            .contains(".results-panel .table-wrap")
            .contains("margin: 16px 16px 0")
            .contains("border: 1px solid var(--border)")
            .contains(".results-panel #process-results")
            .contains("display: block")
            .contains(".results-panel .pagination")
            .contains("margin-top: 16px")
            .doesNotContain(".results-panel #process-results {\n    display: contents;");
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

    @Test
    void adminUiShouldUseLocalAndRelativeDateTimesAndCopyButtons() throws Exception {
        var processesFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/fragments.html"));
        var processDetailsFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/fragments.html"));
        var sharedFragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/shared/fragments.html"));
        var adminJs = Files.readString(Path.of(
                "src/main/resources/ruslo-admin/assets/admin.js"));

        assertThat(processesFragments + processDetailsFragments)
            .contains("class=\"local-date-time\"");
        assertThat(processDetailsFragments)
            .contains("class=\"relative-time\"")
            .contains("copyButton(${entry.value}, 'Copy value')");
        assertThat(processesFragments)
            .contains("copyButton(${process.id}, 'Copy process ID')");
        assertThat(sharedFragments)
            .contains("<symbol id=\"ti-copy\"")
            .contains("th:fragment=\"copyButton(value, label)\"");
        assertThat(adminJs)
            .contains("CopyButtons")
            .contains("applyRelativeTimes")
            .contains("applyLocalDateTimes");
    }

    @Test
    void shellPagesShouldUseSharedTopStrip() throws Exception {
        var dashboardPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/dashboard/page.html"));
        var processesPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/page.html"));
        var processDetailsPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/page.html"));

        assertThat(dashboardPage).contains("topStrip(${breadcrumbs})");
        assertThat(processesPage).contains("topStrip(${breadcrumbs})");
        assertThat(processDetailsPage).contains("topStrip(${breadcrumbs})");
    }

    @Test
    void shellPagesShouldDisableBrowserTranslation() throws Exception {
        var dashboardPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/dashboard/page.html"));
        var processesPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/processes/page.html"));
        var processDetailsPage = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/page.html"));

        assertThat(List.of(dashboardPage, processesPage, processDetailsPage))
            .allSatisfy(page -> assertThat(page)
                .contains("<html lang=\"en\" translate=\"no\"")
                .contains("<meta name=\"google\" content=\"notranslate\">"));
    }

    @Test
    void sharedSidebarShouldNotExposeRemovedDiagramEditor() throws Exception {
        var fragments = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/shared/fragments.html"));

        assertThat(fragments).doesNotContain("diagram-editor");
        assertThat(Path.of("src/main/resources/templates/ruslo-admin/diagram-editor/page.html")).doesNotExist();
        assertThat(Path.of("src/main/resources/ruslo-admin/assets/diagram-editor.js")).doesNotExist();
    }

    @Test
    void processDetailsPageShouldNotCopyOrShortenBreadcrumbProcessId() throws Exception {
        var page = Files.readString(Path.of(
                "src/main/resources/templates/ruslo-admin/process-details/page.html"));

        assertThat(page)
            .doesNotContain("data-copy")
            .doesNotContain("#strings.substring")
            .doesNotContain("#strings.length")
            .doesNotContain("Copy id");
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
                "AWAIT", List.of(), null);
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
