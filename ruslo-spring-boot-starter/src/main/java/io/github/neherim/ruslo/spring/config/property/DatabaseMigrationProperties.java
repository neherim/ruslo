package io.github.neherim.ruslo.spring.config.property;

/**
 * Built-in database schema migration properties.
 */
public class DatabaseMigrationProperties {
    /**
     * Whether to run the built-in database schema migration when the engine starts.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
