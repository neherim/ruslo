package io.github.neherim.ruslo.spring.config.property;

/**
 * Process history properties.
 */
public class HistoryProperties {
    /**
     * Whether to store process state transitions in the database.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
