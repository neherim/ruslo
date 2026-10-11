package io.github.neherim.ruslo.spring.config.property;

public class LoggingProperties {
    private MdcProperties mdc = new MdcProperties();

    public MdcProperties getMdc() {
        return mdc;
    }

    public void setMdc(MdcProperties mdc) {
        this.mdc = mdc;
    }

    public static class MdcProperties {
        private String correlationIdKey = "correlation_id";
        private String processIdKey = "process_id";
        private String processTypeKey = "process_type";

        public String getCorrelationIdKey() {
            return correlationIdKey;
        }

        public void setCorrelationIdKey(String correlationIdKey) {
            this.correlationIdKey = correlationIdKey;
        }

        public String getProcessIdKey() {
            return processIdKey;
        }

        public void setProcessIdKey(String processIdKey) {
            this.processIdKey = processIdKey;
        }

        public String getProcessTypeKey() {
            return processTypeKey;
        }

        public void setProcessTypeKey(String processTypeKey) {
            this.processTypeKey = processTypeKey;
        }

    }
}
