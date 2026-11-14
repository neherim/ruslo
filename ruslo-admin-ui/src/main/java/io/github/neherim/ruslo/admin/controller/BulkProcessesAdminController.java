package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.service.BulkProcessAdminService;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Подтверждение и выполнение массовых действий без серверного хранения заданий. */
@Controller
@RequestMapping("${ruslo.admin-ui.base-path:/ruslo}/htmx/processes/bulk")
public class BulkProcessesAdminController {
    static final String DATABASE_REQUIRED = "Bulk operations require the engine Database bean";
    private static final Logger log = LoggerFactory.getLogger(BulkProcessesAdminController.class);
    private static final JsonMapper JSON = new JsonMapper();
    private final ProcessEngineAdminService adminService;
    private final @Nullable BulkProcessAdminService bulkService;

    public BulkProcessesAdminController(
            ProcessEngineAdminService adminService,
            @Nullable BulkProcessAdminService bulkService
    ) {
        this.adminService = adminService;
        this.bulkService = bulkService;
    }

    /** Подготавливает подтверждение возобновления всех совпадений. */
    @GetMapping("/retry-form")
    public String retryForm(@RequestParam String filters, HttpServletResponse response, Model model) {
        return form(BulkProcessOperation.RETRY, filters, response, model);
    }

    /** Подготавливает подтверждение смены состояния всех совпадений одного типа. */
    @GetMapping("/state-form")
    public String stateForm(@RequestParam String filters, HttpServletResponse response, Model model) {
        return form(BulkProcessOperation.CHANGE_STATE, filters, response, model);
    }

    /** Возобновляет подтверждённый набор и возвращает подробный результат. */
    @PostMapping("/retry")
    public String retry(
            @RequestParam String snapshot,
            HttpServletResponse response,
            Model model
    ) {
        return execute(BulkProcessOperation.RETRY, snapshot, null, response, model);
    }

    /** Переводит подтверждённый набор в выбранное состояние и возвращает подробный результат. */
    @PostMapping("/state")
    public String state(
            @RequestParam String snapshot,
            @RequestParam String targetState,
            HttpServletResponse response,
            Model model
    ) {
        return execute(BulkProcessOperation.CHANGE_STATE, snapshot, targetState, response, model);
    }

    static String serialize(Object value) {
        return JSON.writeValueAsString(value);
    }

    private String form(BulkProcessOperation operation, String filters, HttpServletResponse response, Model model) {
        try {
            var snapshot = requireService().prepare(operation, read(filters, ProcessSearchRequest.class));
            model.addAttribute("operation", operation);
            model.addAttribute("snapshot", snapshot);
            model.addAttribute("snapshotJson", serialize(snapshot));
            model.addAttribute("states", operation == BulkProcessOperation.CHANGE_STATE
                    ? adminService.statesFor(snapshot.filter().typeName(), "")
                    : List.of());
            return "ruslo-admin/processes/bulk :: confirmation";
        } catch (RuntimeException ex) {
            log.warn("Could not prepare bulk {} selection", operation, ex);
            return invalid(response, model, ex);
        }
    }

    private String execute(
            BulkProcessOperation operation,
            String json,
            @Nullable String state,
            HttpServletResponse response,
            Model model
    ) {
        long started = System.nanoTime();
        try {
            var service = requireService();
            var snapshot = read(json, BulkProcessSnapshot.class);
            log.info("Admin UI bulk action: {}, target={}, filters={}, selected={}", operation, state,
                    snapshot.filter().activeFilters(), snapshot.processes().size());
            var result = operation == BulkProcessOperation.RETRY
                    ? service.retry(snapshot)
                    : service.changeState(snapshot, state);
            model.addAttribute("bulkResult", result);
            if (result.interruptedProcessId() != null) response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            var kind = result.interruptedProcessId() != null
                    ? "error"
                    : result.skipped().isEmpty() ? "success" : "info";
            response.setHeader("HX-Trigger", HtmxResponseSupport.buildEventAndToastTrigger(
                    HtmxResponseSupport.PROCESS_LIST_CHANGED, kind, result.summary()));
            log.info("Admin UI bulk action finished: {}, {}, durationMs={}", operation, result.summary(),
                    (System.nanoTime() - started) / 1_000_000);
            return "ruslo-admin/processes/bulk :: result";
        } catch (RuntimeException ex) {
            log.warn("Admin UI bulk action failed: {}", operation, ex);
            return invalid(response, model, ex);
        }
    }

    private BulkProcessAdminService requireService() {
        if (bulkService == null) throw new IllegalArgumentException(DATABASE_REQUIRED);
        return bulkService;
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            var value = JSON.readValue(json, type);
            if (value == null) throw new IllegalArgumentException("Selection is required");
            return value;
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Invalid process selection JSON");
        }
    }

    private static String invalid(HttpServletResponse response, Model model, RuntimeException ex) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("error", true);
        model.addAttribute("status", ex instanceof IllegalArgumentException
                ? ex.getMessage()
                : "Could not complete the bulk request. Check the application log.");
        return "ruslo-admin/shared/fragments :: statusLine";
    }
}
