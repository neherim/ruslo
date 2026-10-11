package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import io.github.neherim.ruslo.admin.view.Breadcrumb;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneId;
import java.util.List;

/**
 * Admin UI dashboard page.
 */
@Controller
@RequestMapping("${ruslo.admin-ui.base-path:/ruslo}")
public class DashboardAdminController {
    private final ProcessEngineAdminService adminService;

    public DashboardAdminController(ProcessEngineAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Renders the dashboard page shell; its content is loaded by {@link #dashboardContent}.
     */
    @GetMapping({"", "/"})
    public String dashboard(Model model) {
        model.addAttribute("breadcrumbs", List.of(Breadcrumb.current("Dashboard")));
        return "ruslo-admin/dashboard/page";
    }

    /**
     * Renders the dashboard content: process counters, process definitions and engine instances.
     *
     * @param timeZone browser time zone for the link to overdue processes; the server zone if absent
     */
    @GetMapping("/htmx/dashboard")
    public String dashboardContent(@RequestParam(required = false) String timeZone, Model model) {
        var zone = timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
        model.addAttribute("dashboard", adminService.dashboard(zone));
        model.addAttribute("definitions", adminService.definitions());
        return "ruslo-admin/dashboard/fragments :: dashboard";
    }
}
