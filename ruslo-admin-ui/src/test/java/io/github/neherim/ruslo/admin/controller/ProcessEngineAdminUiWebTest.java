package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dev.process.OrderProcessDefinition;
import io.github.neherim.ruslo.admin.dto.ProcessPageResponse;
import io.github.neherim.ruslo.admin.dto.ProcessSummaryResponse;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the admin UI in a servlet application context: assets, context path, response codes and search parameters.
 */
@SpringBootTest(
        classes = ProcessEngineAdminUiWebTest.TestApplication.class,
        properties = {
                "ruslo.dispatcher.enabled=false",
                "ruslo.admin-ui.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:admin-ui-web-test;DB_CLOSE_DELAY=-1"
        }
)
class ProcessEngineAdminUiWebTest {
    private static final ZoneId BROWSER_ZONE = ZoneId.of("Pacific/Kiritimati");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProcessEngine processEngine;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void shouldServeAssetsUnderBasePathWithRevalidation() throws Exception {
        mockMvc.perform(get("/ruslo/assets/admin.js"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        mockMvc.perform(get("/ruslo/assets/vendor/cytoscape/3.34.3/cytoscape.min.js"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldResolvePageLinksAgainstContextPath() throws Exception {
        mockMvc.perform(get("/app/ruslo/processes").contextPath("/app"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/app/ruslo/\">")))
            .andExpect(content().string(containsString("href=\"assets/admin.css\"")))
            .andExpect(content().string(containsString("src=\"assets/admin.js\"")));
    }

    @Test
    void shouldReturnNotFoundForUnknownProcess() throws Exception {
        mockMvc.perform(get("/ruslo/processes/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/ruslo/htmx/processes/{id}/details", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnUnprocessableStatusLineWhenActionFails() throws Exception {
        var processId = processEngine.newProcess(OrderProcessDefinition.TYPE).start();

        mockMvc.perform(post("/ruslo/htmx/processes/{id}/variables", processId)
            .param("name", "customer")
            .param("jsonValue", "{bad"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(content().string(containsString("error\">Invalid JSON value</div>")))
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")));
    }

    @Test
    void shouldRemoveVariableWithReservedCharactersInName() throws Exception {
        var name = "a/b?c#d%e";
        var processId = processEngine.newProcess(OrderProcessDefinition.TYPE).variable(name, 1).start();

        var html = mockMvc.perform(get("/ruslo/processes/{id}", processId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(html).contains("hx-delete=\"htmx/processes/" + processId + "/variables?name=a/b?c%23d%25e\"");

        mockMvc.perform(delete("/ruslo/htmx/processes/{id}/variables", processId).param("name", name))
            .andExpect(status().isOk());

        assertThat(processEngine.processes().byId(processId).singleDetails().orElseThrow().variables().asJsonMap())
            .doesNotContainKey(name);
    }

    @Test
    void shouldKeepPageSizeAndOffsetWithinBounds() throws Exception {
        var page = (ProcessPageResponse) mockMvc.perform(get("/ruslo/processes")
            .param("limit", "1000000")
            .param("offset", "-5"))
            .andExpect(status().isOk())
            .andReturn()
            .getModelAndView()
            .getModel()
            .get("page");

        assertThat(page.limit()).isEqualTo(100);
        assertThat(page.offset()).isEqualTo(0);
    }

    @Test
    void shouldReadDateFiltersInBrowserTimeZone() throws Exception {
        var processId = processEngine.newProcess(OrderProcessDefinition.TYPE).start();
        var browserNow = LocalDateTime.now(BROWSER_ZONE).truncatedTo(ChronoUnit.MINUTES);

        assertThat(searchCreatedFrom(browserNow.minusMinutes(5))).contains(processId);
        assertThat(searchCreatedFrom(browserNow.plusMinutes(5))).doesNotContain(processId);
    }

    @SuppressWarnings("unchecked")
    private List<UUID> searchCreatedFrom(LocalDateTime createdFrom) throws Exception {
        var processes = (List<ProcessSummaryResponse>) mockMvc.perform(get("/ruslo/processes")
            .param("createdAtFrom", createdFrom.toString())
            .param("timeZone", BROWSER_ZONE.getId()))
            .andExpect(status().isOk())
            .andReturn()
            .getModelAndView()
            .getModel()
            .get("processes");
        return processes.stream().map(ProcessSummaryResponse::id).toList();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(OrderProcessDefinition.class)
    static class TestApplication {
    }
}
