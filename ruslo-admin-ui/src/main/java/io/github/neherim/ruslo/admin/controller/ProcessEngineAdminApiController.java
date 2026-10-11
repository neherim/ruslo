package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.ProcessGraphResponse;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Ruslo admin UI REST API.
 */
@RestController
@RequestMapping("${ruslo.admin-ui.base-path:/ruslo}/api")
public class ProcessEngineAdminApiController {
    private final ProcessEngineAdminService adminService;

    public ProcessEngineAdminApiController(ProcessEngineAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Returns the state and transition graph of a process definition for the diagram, or 404 if no definition
     * is registered for {@code typeName}.
     */
    @GetMapping(value = "/definitions/{typeName}/graph", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProcessGraphResponse> graph(@PathVariable String typeName) {
        return ResponseEntity.of(adminService.graph(typeName));
    }
}
