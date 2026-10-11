package io.github.neherim.ruslo.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ruslo admin UI properties.
 */
@ConfigurationProperties(prefix = "ruslo.admin-ui", ignoreUnknownFields = false)
public class ProcessEngineAdminUiProperties {
    /**
     * Whether to enable the admin UI.
     */
    private boolean enabled = false;
    /**
     * Base path of the admin UI pages, REST API and static files.
     */
    private String basePath = "/ruslo";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
