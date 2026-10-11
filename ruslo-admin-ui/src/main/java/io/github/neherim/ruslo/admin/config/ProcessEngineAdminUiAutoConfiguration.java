package io.github.neherim.ruslo.admin.config;

import io.github.neherim.ruslo.admin.controller.*;
import io.github.neherim.ruslo.admin.service.ProcessEngineAdminService;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuration of the Ruslo admin UI.
 */
@AutoConfiguration(afterName = "io.github.neherim.ruslo.spring.config.ProcessEngineAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ProcessEngine.class, Controller.class})
@ConditionalOnBean(ProcessEngine.class)
@ConditionalOnProperty(prefix = "ruslo.admin-ui", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ProcessEngineAdminUiProperties.class)
public class ProcessEngineAdminUiAutoConfiguration implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(ProcessEngineAdminUiAutoConfiguration.class);
    private static final String SPRING_SECURITY_CLASS = "org.springframework.security.web.SecurityFilterChain";
    private static final String ASSETS_LOCATION = "classpath:/ruslo-admin/assets/";

    private final ProcessEngineAdminUiProperties properties;

    /**
     * Logs a warning if Spring Security is not on the classpath: the UI is then available without authentication.
     */
    public ProcessEngineAdminUiAutoConfiguration(ProcessEngineAdminUiProperties properties) {
        this.properties = properties;
        if (!ClassUtils.isPresent(SPRING_SECURITY_CLASS, getClass().getClassLoader())) {
            log.warn("Ruslo admin UI at '{}' is available without authentication: Spring Security is not "
                    + "on the classpath. Protect it with Spring Security or disable it with "
                    + "ruslo.admin-ui.enabled=false", properties.getBasePath());
        }
    }

    /**
     * Serves the UI static files at {@code ${base-path}/assets/**}. The browser revalidates them on every load, so
     * stale versions do not stay cached after a library upgrade. A handler the application already registered for
     * this path is kept.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var pattern = properties.getBasePath() + "/assets/**";
        if (registry.hasMappingForPattern(pattern)) {
            return;
        }
        registry.addResourceHandler(pattern)
                .addResourceLocations(ASSETS_LOCATION)
                .setCacheControl(CacheControl.noCache());
    }

    /**
     * Returns the dashboard page controller.
     */
    @Bean
    public DashboardAdminController dashboardAdminController(ProcessEngineAdminService adminService) {
        return new DashboardAdminController(adminService);
    }

    /**
     * Returns the processes section controller.
     */
    @Bean
    public ProcessesAdminController processesAdminController(ProcessEngineAdminService adminService) {
        return new ProcessesAdminController(adminService);
    }

    /**
     * Returns the service behind the UI pages and the REST API.
     */
    @Bean
    public ProcessEngineAdminService processEngineAdminService(ProcessEngine processEngine, ProcessEngineAdmin management) {
        return new ProcessEngineAdminService(processEngine, management);
    }

    /**
     * Returns the REST API controller.
     */
    @Bean
    public ProcessEngineAdminApiController processEngineAdminApiController(ProcessEngineAdminService adminService) {
        return new ProcessEngineAdminApiController(adminService);
    }

    /**
     * Returns the provider of model attributes shared by all UI pages.
     */
    @Bean
    public ProcessEngineAdminModelAttributes processEngineAdminModelAttributes() {
        return new ProcessEngineAdminModelAttributes(properties);
    }
}
