package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.ProcessGraphResponse;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProcessEngineAdminApiControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProcessEngineAdminApiController(new GraphAdminService()))
            .build();

    @Test
    void shouldExposeProcessGraph() throws Exception {
        mockMvc.perform(get("/ruslo/api/definitions/{type}/graph", "ORDER"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.initialState").value("CREATED"))
                .andExpect(jsonPath("$.nodes[0].id").value("CREATED"))
                .andExpect(jsonPath("$.nodes[0].label").value("Create order"))
                .andExpect(jsonPath("$.nodes[0].kind").value("STEP"))
                .andExpect(jsonPath("$.nodes[0].worker").value("DEFAULT"))
                .andExpect(jsonPath("$.edges[0].source").value("CREATED"))
                .andExpect(jsonPath("$.edges[0].target").value("DONE"))
                .andExpect(jsonPath("$.edges[0].trigger").value("STEP"));
    }

    @Test
    void shouldReturnNotFoundForUnknownProcessType() throws Exception {
        mockMvc.perform(get("/ruslo/api/definitions/{type}/graph", "UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldNotExposeRemovedBpmnEndpoint() throws Exception {
        mockMvc.perform(get("/ruslo/api/definitions/{type}/bpmn", "ORDER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldNotExposeDuplicatedJsonApiEndpoints() throws Exception {
        mockMvc.perform(get("/ruslo/api/dashboard"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ruslo/api/definitions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ruslo/api/processes"))
                .andExpect(status().isNotFound());
    }

    private static class GraphAdminService extends ProcessEngineAdminService {
        private GraphAdminService() {
            super(null, null);
        }

        @Override
        public Optional<ProcessGraphResponse> graph(String typeName) {
            if ("ORDER".equals(typeName)) {
                return Optional.of(new ProcessGraphResponse("CREATED",
                    List.of(new ProcessGraphResponse.Node("CREATED", "Create order", "STEP", "DEFAULT"),
                        new ProcessGraphResponse.Node("DONE", "DONE", "TERMINAL", null)),
                    List.of(new ProcessGraphResponse.Edge("CREATED", "DONE", "STEP", null))));
            }
            return Optional.empty();
        }
    }
}
