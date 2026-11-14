package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.*;
import io.github.neherim.ruslo.admin.service.BulkProcessAdminService;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkProcessesAdminControllerTest {
    @Test
    void missingDatabaseRejectsPreviewAndDirectPostWithoutBreakingExistingPages() {
        var controller = new BulkProcessesAdminController(null, null);
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();
        controller.retryForm("{}", response, model);
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(model.getAttribute("status")).isEqualTo(BulkProcessesAdminController.DATABASE_REQUIRED);
        response = new MockHttpServletResponse();
        controller.retry("{}", new MockHttpServletRequest(), response, model);
        assertThat(response.getStatus()).isEqualTo(422);
    }

    @Test
    void partialResultIsRenderedAs422AndStillRefreshesTheList() {
        var interruptedId = UUID.randomUUID();
        var service = new BulkProcessAdminService(null, null, null) {
            @Override
            public BulkProcessResult retry(BulkProcessSnapshot snapshot) {
                return new BulkProcessResult(3, 1, List.of(), new BulkProcessResult.Interruption(interruptedId), 1);
            }
        };
        var controller = new BulkProcessesAdminController(null, service);
        var filter = new ProcessSearchRequest(null, null, null, ProcessStatus.FAILED,
                null, null, null, null, null, null, null, null, null, null);
        var snapshot = new BulkProcessSnapshot(filter, Instant.now().toString(), List.of());
        var response = new MockHttpServletResponse();
        var model = new ConcurrentModel();

        var view = controller.retry(BulkProcessesAdminController.serialize(snapshot), new MockHttpServletRequest(),
                response, model);

        assertThat(view).isEqualTo("ruslo-admin/processes/bulk :: result");
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getHeader("HX-Trigger")).contains("processListChanged", "\"kind\":\"error\"");
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        var templates = new SpringTemplateEngine();
        templates.setTemplateResolver(resolver);
        var context = new Context();
        context.setVariables(model.asMap());
        var html = templates.process("ruslo-admin/processes/bulk", Set.of("result"), context);
        assertThat(html).contains(interruptedId.toString(), "Outcome not confirmed",
                "Earlier successful changes remain committed", "1 not attempted");
    }
}
