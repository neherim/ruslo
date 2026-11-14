package io.github.neherim.ruslo.admin.dto;

/** Bulk actions available for a confirmed process selection. */
public enum BulkProcessOperation {
    RETRY("Retry", "retry"),
    CHANGE_STATE("Change state", "state");

    private final String label;
    private final String path;

    BulkProcessOperation(String label, String path) {
        this.label = label;
        this.path = path;
    }

    public String label() {
        return label;
    }

    public String path() {
        return path;
    }
}
