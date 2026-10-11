package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.config.ProcessEngineAdminUiProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes shared by all admin UI pages.
 */
@ControllerAdvice(basePackageClasses = ProcessEngineAdminModelAttributes.class)
public class ProcessEngineAdminModelAttributes {
    private final ProcessEngineAdminUiProperties properties;

    public ProcessEngineAdminModelAttributes(ProcessEngineAdminUiProperties properties) {
        this.properties = properties;
    }

    /**
     * Exposes the admin UI base path to templates as {@code basePath}.
     */
    @ModelAttribute("basePath")
    public String basePath() {
        return properties.getBasePath();
    }
}
