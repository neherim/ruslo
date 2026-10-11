package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.ProcessDetailsResponse;
import io.github.neherim.ruslo.admin.dto.ProcessSearchRequest;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import io.github.neherim.ruslo.admin.view.Breadcrumb;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.UUID;

/**
 * Admin UI processes section: the process list, the process page and process actions.
 * <p>
 * Each action logs itself with the current user and returns a status line fragment; the {@code HX-Trigger}
 * header shows a toast and, on success, fires an event that refreshes the affected view. A failed action
 * returns 422 with an error toast.
 */
@Controller
@RequestMapping("${ruslo.admin-ui.base-path:/ruslo}")
public class ProcessesAdminController {
    private static final Logger log = LoggerFactory.getLogger(ProcessesAdminController.class);
    private static final String PROCESSES_PATH = "processes";
    private static final String PROCESSES_LINK = "./processes";
    private final ProcessEngineAdminService adminService;

    public ProcessesAdminController(ProcessEngineAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Renders the process list.
     */
    @GetMapping("/processes")
    public String processes(@ModelAttribute ProcessSearchRequest searchForm, HttpServletRequest request, Model model) {
        var definitions = adminService.definitions();
        var states = ProcessDefinitionFilterOptions.stateNames(definitions, searchForm.typeName());
        var page = adminService.searchProcesses(searchForm);
        model.addAttribute("searchForm", searchForm);
        model.addAttribute("definitions", definitions);
        model.addAttribute("states", states);
        model.addAttribute("processes", page.items());
        model.addAttribute("page", page);
        model.addAttribute("returnTo", processesReturnTo(request));
        model.addAttribute("status", "Loaded " + page.items().size() + " processes");
        model.addAttribute("breadcrumbs", List.of(Breadcrumb.current("Processes")));
        return "ruslo-admin/processes/page";
    }

    /**
     * Renders the process page.
     *
     * @param returnTo relative address of the process list to return to
     */
    @GetMapping("/processes/{processId}")
    public String processDetails(@PathVariable UUID processId,
                                 @RequestParam(required = false) String returnTo,
                                 Model model) {
        var process = findProcess(processId);
        model.addAttribute("processId", processId);
        model.addAttribute("process", process);
        model.addAttribute("states", adminService.statesFor(process.type(), process.state()));
        model.addAttribute("breadcrumbs", List.of(
                Breadcrumb.link("Processes", processesBreadcrumbHref(returnTo)),
                Breadcrumb.current(processId.toString())));
        return "ruslo-admin/process-details/page";
    }

    /**
     * Renders the state filter options: states of {@code typeName}, or distinct state names of all registered
     * definitions if it is absent.
     */
    @GetMapping("/htmx/definitions/state-options")
    public String definitionStateOptions(@RequestParam(required = false) String typeName, Model model) {
        model.addAttribute("states", ProcessDefinitionFilterOptions.stateNames(adminService.definitions(), typeName));
        return "ruslo-admin/processes/fragments :: stateFilterOptions";
    }

    /**
     * Renders the process start form; a given {@code type} is preselected and locked.
     */
    @GetMapping("/htmx/processes/start-form")
    public String startProcessForm(@RequestParam(required = false) String type, Model model) {
        var definitions = adminService.definitions();
        model.addAttribute("definitions", definitions);
        if (type != null && !type.isBlank()) {
            model.addAttribute("lockedType", type);
        }
        return "ruslo-admin/processes/fragments :: startProcessForm";
    }

    /**
     * Starts a process from the start form.
     *
     * @param variables JSON object of process variables
     */
    @PostMapping("/htmx/processes")
    public String startProcess(@RequestParam String type,
                               @RequestParam(required = false) String businessKey,
                               @RequestParam(required = false, defaultValue = "{}") String variables,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               Model model) {
        return statusLine(request, response, model, "start process of type " + type, () -> {
            var processId = adminService.startProcess(type, businessKey, variables);
            success(response, model, HtmxResponseSupport.PROCESS_LIST_CHANGED, "Started " + processId);
        });
    }

    /**
     * Renders the process page content, for a refresh after an action and for polling. Polling passes the
     * version of the displayed content: if the process has not changed, the response is {@code 204} with no body
     * and htmx keeps the content.
     *
     * @param since version of the content already displayed in the browser
     */
    @GetMapping("/htmx/processes/{processId}/details")
    public ModelAndView processDetails(@PathVariable UUID processId,
                                       @RequestParam(required = false) String since,
                                       HttpServletResponse response) {
        var process = findProcess(processId);
        if (process.version().equals(since)) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
            return null;
        }
        var view = new ModelAndView("ruslo-admin/process-details/fragments :: processDetailsContent");
        view.addObject("process", process);
        view.addObject("states", adminService.statesFor(process.type(), process.state()));
        return view;
    }

    /**
     * Moves the process to {@code state}.
     */
    @PostMapping("/htmx/processes/{processId}/state")
    public String changeState(@PathVariable UUID processId,
                              @RequestParam String state,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Model model) {
        return statusLine(request, response, model, "move process " + processId + " to state " + state, () -> {
            adminService.changeState(processId, state);
            success(response, model, HtmxResponseSupport.PROCESS_DETAILS_CHANGED, "State changed");
        });
    }

    /**
     * Schedules the current step of the process for another run.
     */
    @PostMapping("/htmx/processes/{processId}/retry-current-step")
    public String retryCurrentStep(@PathVariable UUID processId,
                                   HttpServletRequest request,
                                   HttpServletResponse response,
                                   Model model) {
        return statusLine(request, response, model, "retry current step of process " + processId, () -> {
            adminService.retryCurrentStep(processId);
            success(response, model, HtmxResponseSupport.PROCESS_DETAILS_CHANGED, "Retry scheduled");
        });
    }

    /**
     * Sends a message to the process.
     *
     * @param jsonValue message value as JSON
     */
    @PostMapping("/htmx/processes/{processId}/messages")
    public String sendMessage(@PathVariable UUID processId,
                              @RequestParam String name,
                              @RequestParam String jsonValue,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Model model) {
        return statusLine(request, response, model, "send message " + name + " to process " + processId, () -> {
            adminService.sendMessage(processId, name, jsonValue);
            success(response, model, HtmxResponseSupport.PROCESS_DETAILS_CHANGED, "Message sent");
        });
    }

    /**
     * Sets a process variable.
     *
     * @param jsonValue variable value as JSON
     */
    @PostMapping("/htmx/processes/{processId}/variables")
    public String setVariable(@PathVariable UUID processId,
                              @RequestParam String name,
                              @RequestParam String jsonValue,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Model model) {
        return statusLine(request, response, model, "set variable " + name + " of process " + processId, () -> {
            adminService.setVariable(processId, name, jsonValue);
            success(response, model, HtmxResponseSupport.PROCESS_DETAILS_CHANGED, "Variable saved");
        });
    }

    /**
     * Removes a process variable; a missing variable is reported as info, not as an error.
     */
    @DeleteMapping("/htmx/processes/{processId}/variables")
    public String removeVariable(@PathVariable UUID processId,
                                 @RequestParam String name,
                                 HttpServletRequest request,
                                 HttpServletResponse response,
                                 Model model) {
        return statusLine(request, response, model, "remove variable " + name + " of process " + processId, () -> {
            var removed = adminService.removeVariable(processId, name);
            success(response,
                    model,
                    removed ? HtmxResponseSupport.PROCESS_DETAILS_CHANGED : null,
                    removed ? "success" : "info",
                    removed ? "Variable removed" : "Variable was not found");
        });
    }

    private ProcessDetailsResponse findProcess(UUID processId) {
        return adminService.process(processId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found: " + processId));
    }

    /**
     * Runs an action as described in the class Javadoc.
     */
    private static String statusLine(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Model model,
                                     String description,
                                     Runnable action) {
        var user = request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName();
        try {
            action.run();
            log.info("Admin UI action by {}: {}", user, description);
        } catch (RuntimeException ex) {
            log.warn("Admin UI action by {} failed: {}", user, description, ex);
            error(response, model, ex);
        }
        return "ruslo-admin/shared/fragments :: statusLine";
    }

    private static void success(HttpServletResponse response, Model model, String event, String status) {
        success(response, model, event, "success", status);
    }

    private static void success(HttpServletResponse response, Model model, String event, String kind, String status) {
        response.addHeader("HX-Trigger", HtmxResponseSupport.buildEventAndToastTrigger(event, kind, status));
        model.addAttribute("status", status);
        model.addAttribute("error", false);
    }

    private static void error(HttpServletResponse response, Model model, RuntimeException ex) {
        var message = HtmxResponseSupport.errorMessage(ex);
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        response.addHeader("HX-Trigger", HtmxResponseSupport.buildToastTrigger("error", message));
        model.addAttribute("status", message);
        model.addAttribute("error", true);
    }

    private static String processesReturnTo(HttpServletRequest request) {
        var queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return PROCESSES_PATH;
        }
        return PROCESSES_PATH + "?" + queryString;
    }

    private static String processesBreadcrumbHref(String returnTo) {
        if (isValidProcessesReturnTo(returnTo)) {
            return "./" + returnTo;
        }
        return PROCESSES_LINK;
    }

    private static boolean isValidProcessesReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        var queryStart = returnTo.indexOf('?');
        var path = queryStart < 0 ? returnTo : returnTo.substring(0, queryStart);
        return PROCESSES_PATH.equals(path);
    }
}
