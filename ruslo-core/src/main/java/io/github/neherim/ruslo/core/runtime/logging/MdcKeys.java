package io.github.neherim.ruslo.core.runtime.logging;

/** MDC keys the engine uses when logging a process. A {@code null} or blank key is replaced by the default key. */
public record MdcKeys(String correlationId, String processId, String processType) {

    public static final String DEFAULT_CORRELATION_ID = "correlation_id";
    public static final String DEFAULT_PROCESS_ID = "process_id";
    public static final String DEFAULT_PROCESS_TYPE = "process_type";

    public MdcKeys {
        correlationId = orDefault(correlationId, DEFAULT_CORRELATION_ID);
        processId = orDefault(processId, DEFAULT_PROCESS_ID);
        processType = orDefault(processType, DEFAULT_PROCESS_TYPE);
    }

    /** Returns the default MDC keys. */
    public static MdcKeys defaults() {
        return new MdcKeys(null, null, null);
    }

    private static String orDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
