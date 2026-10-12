package io.github.neherim.ruslo.admin.config;

import io.github.neherim.ruslo.admin.controller.DashboardAdminController;
import io.github.neherim.ruslo.admin.controller.ProcessEngineAdminApiController;
import io.github.neherim.ruslo.admin.controller.ProcessEngineAdminModelAttributes;
import io.github.neherim.ruslo.admin.controller.ProcessesAdminController;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.management.DatabaseSchemaMigrator;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessEngineAdminUiAutoConfigurationTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProcessEngineAdminUiAutoConfiguration.class))
        .withBean(ProcessEngine.class, this::processEngine)
        .withBean(ProcessEngineAdmin.class, () -> new ProcessEngineAdmin(processEngine()));

    @Test
    void shouldRegisterAdminUiBeans() {
        contextRunner.withPropertyValues("ruslo.admin-ui.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ProcessEngineAdminUiProperties.class);
            assertThat(context.getBean(ProcessEngineAdminUiProperties.class).isEnabled()).isTrue();
            assertThat(context).hasSingleBean(ProcessEngineAdminService.class);
            assertThat(context).hasSingleBean(DashboardAdminController.class);
            assertThat(context).hasSingleBean(ProcessesAdminController.class);
            assertThat(context).hasSingleBean(ProcessEngineAdminApiController.class);
            assertThat(context).hasSingleBean(ProcessEngineAdminModelAttributes.class);
        });
    }

    @Test
    void shouldNotRegisterAdminUiBeansByDefault() {
        assertThat(new ProcessEngineAdminUiProperties().isEnabled()).isFalse();
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ProcessEngineAdminUiAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(ProcessEngineAdminUiProperties.class);
            assertThat(context).doesNotHaveBean(ProcessEngineAdminService.class);
            assertThat(context).doesNotHaveBean(DashboardAdminController.class);
            assertThat(context).doesNotHaveBean(ProcessesAdminController.class);
            assertThat(context).doesNotHaveBean(ProcessEngineAdminApiController.class);
            assertThat(context).doesNotHaveBean(ProcessEngineAdminModelAttributes.class);
        });
    }

    @Test
    void shouldDisableAdminUiBeansByProperty() {
        contextRunner
            .withPropertyValues("ruslo.admin-ui.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(DashboardAdminController.class);
                assertThat(context).doesNotHaveBean(ProcessesAdminController.class);
                assertThat(context).doesNotHaveBean(ProcessEngineAdminApiController.class);
                assertThat(context).doesNotHaveBean(ProcessEngineAdminModelAttributes.class);
            });
    }

    @Test
    void shouldRejectUnknownAdminUiPropertyWhenEnabled() {
        contextRunner
            .withPropertyValues("ruslo.admin-ui.enabled=true", "ruslo.admin-ui.base-pth=/processes")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldNotRegisterAdminUiBeansOutsideServletWebApplication() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProcessEngineAdminUiAutoConfiguration.class))
            .withPropertyValues("ruslo.admin-ui.enabled=true")
            .withBean(ProcessEngine.class, this::processEngine)
            .withBean(ProcessEngineAdmin.class, () -> new ProcessEngineAdmin(processEngine()))
            .run(context -> assertThat(context).doesNotHaveBean(ProcessesAdminController.class));
    }

    private ProcessEngine processEngine() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        DatabaseSchemaMigrator.update(dataSource);
        return new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .registerWorker(WorkerSpec.builder(WorkerId.DEFAULT)
                .transactional()
                .build())
            .build()
            .start();
    }
}
