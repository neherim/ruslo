package io.github.neherim.ruslo.admin.dto;

/** Операции над подтверждённой выборкой процессов в админке. */
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
