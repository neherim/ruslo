package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dev.process.OrderProcessDefinition;
import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        classes = ProcessEngineAdminUiWebTest.TestApplication.class,
        properties = {
                "ruslo.dispatcher.enabled=false",
                "ruslo.admin-ui.enabled=true",
                "ruslo.admin-ui.base-path=/ops",
                "spring.datasource.url=jdbc:h2:mem:bulk-web;DB_CLOSE_DELAY=-1"
        }
)
class BulkProcessesAdminWebTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ProcessEngine engine;
    @Autowired
    private Database database;
    private MockMvc mvc;
    private String key;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        key = UUID.randomUUID().toString();
    }

    @Test
    void rendersBothActionsAndKeepsAppliedFiltersAndCsrfWithCustomBaseAndContextPath() throws Exception {
        var result = mvc.perform(get("/app/ops/processes").contextPath("/app")
            .param("typeName", "ORDER")
            .param("status", "FAILED")
            .param("businessKey", "<script>\"&")
            .param("limit", "1")
            .param("offset", "200")
            .param("createdAtFrom", "2026-01-01T14:00")
            .param("timeZone", "Pacific/Kiritimati")
            .flashAttr("_csrf", new TestCsrf("csrf-token", "X-CSRF-TEST")))
            .andExpect(status().isOk())
            .andReturn();
        var html = result.getResponse().getContentAsString();
        assertThat(html).contains("<base href=\"/app/ops/\">", "Retry all matching", "Change state for all matching",
                "data-bulk-preview", "X-CSRF-TEST", "csrf-token", "processListChanged from:body", "data-keep-open");
        assertThat(html).doesNotContain("value=\"<script>");
        var json = (String) result.getModelAndView().getModel().get("bulkFilters");
        assertThat(json).contains("2026-01-01T00:00:00Z", "\"limit\":null", "\"offset\":null");
        assertThat(html).contains("name=\"filters\"");
    }

    @Test
    void previewAndExecutionKeepConfirmedIdsSkipChangesAndRefreshTheList() throws Exception {
        var first = failed();
        var second = failed();
        var prepared = preview("retry", filter(ProcessStatus.FAILED));
        var snapshot = (BulkProcessSnapshot) prepared.getModelAndView().getModel().get("snapshot");
        var html = prepared.getResponse().getContentAsString();
        assertThat(snapshot.processes()).hasSize(2);
        assertThat(html).contains("All matching processes across all pages", "Retry 2 processes",
                "Showing 2 of 2 selected processes", "name=\"snapshot\"", "hx-post=\"htmx/processes/bulk/retry\"");
        assertThat(statusOf(first)).isEqualTo(ProcessStatus.FAILED);
        var later = failed();
        new ProcessEngineAdmin(engine).moveProcessToState(first, OrderProcessDefinition.State.MANUAL_REVIEW);

        mvc.perform(post("/ops/htmx/processes/bulk/retry")
            .param("snapshot", (String) prepared.getModelAndView().getModel().get("snapshotJson")))
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Trigger", containsString("processListChanged")))
            .andExpect(content().string(containsString("1 succeeded, 1 skipped, 0 not attempted")))
            .andExpect(content().string(containsString("data-bulk-result")))
            .andExpect(content().string(containsString(first.toString())));
        assertThat(statusOf(second)).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(statusOf(later)).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    void stateConfirmationUsesRegisteredStatesAndTerminalMoveCompletesProcess() throws Exception {
        var id = failed();
        var prepared = preview("state", filter(null));
        assertThat(prepared.getResponse().getContentAsString()).contains("Target state", "COMPLETED", "Order completed",
                "Moving to another state clears queued messages");
        mvc.perform(post("/ops/htmx/processes/bulk/state")
            .param("snapshot", (String) prepared.getModelAndView().getModel().get("snapshotJson"))
            .param("targetState", "COMPLETED"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("1 succeeded, 0 skipped")));
        assertThat(statusOf(id)).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void rejectsDuplicateSelectionAndUnknownTarget() throws Exception {
        var id = failed();
        var prepared = preview("state", filter(null));
        var snapshot = (BulkProcessSnapshot) prepared.getModelAndView().getModel().get("snapshot");
        var duplicate = new BulkProcessSnapshot(snapshot.filter(), snapshot.preparedAt(),
                List.of(snapshot.processes().getFirst(), snapshot.processes().getFirst()));
        mvc.perform(post("/ops/htmx/processes/bulk/state")
            .param("snapshot", BulkProcessesAdminController.serialize(duplicate))
            .param("targetState", "COMPLETED"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(content().string(containsString("Duplicate process ID")));
        mvc.perform(post("/ops/htmx/processes/bulk/state")
            .param("snapshot", BulkProcessesAdminController.serialize(snapshot))
            .param("targetState", "BOGUS"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(content().string(containsString("Select a state declared")));
        assertThat(statusOf(id)).isEqualTo(ProcessStatus.FAILED);
    }

    private MvcResult preview(String operation, ProcessSearchRequest filter) throws Exception {
        return mvc.perform(get("/ops/htmx/processes/bulk/" + operation + "-form")
            .param("filters", BulkProcessesAdminController.serialize(filter)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private UUID failed() {
        var id = engine.newProcess(OrderProcessDefinition.TYPE).businessKey(key).start();
        database.update("update xs_process_instance set status = 'FAILED', next_run_at = null where id = ?", id);
        return id;
    }

    private ProcessStatus statusOf(UUID id) {
        return engine.processes().byId(id).single().orElseThrow().status();
    }

    private ProcessSearchRequest filter(ProcessStatus status) {
        return new ProcessSearchRequest(null, "ORDER", null, status, key, null,
                null, null, null, null, null, 1, 500, "UTC");
    }

    public record TestCsrf(String token, String headerName) {
    }
}
