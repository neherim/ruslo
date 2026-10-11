package io.github.neherim.ruslo.spring.config.property;

import java.time.Duration;

public class DispatcherProperties {
    /**
     * Whether to start the dispatcher and register the instance when the engine starts.
     */
    private boolean enabled = true;
    /**
     * Maximum interval between polls for ready processes, used when there is no work.
     */
    private Duration maxInterval = Duration.ofSeconds(10);

    /**
     * Minimum interval between polls for ready processes, used when there is work.
     */
    private Duration minInterval = Duration.ofMillis(100);

    /**
     * Interval between instance heartbeats.
     */
    private Duration instanceHeartbeatInterval = Duration.ofSeconds(15);

    /**
     * How long an instance without heartbeats stays registered before its leases are released, in database time.
     * Must be at least four heartbeat intervals.
     */
    private Duration instanceTimeout = Duration.ofSeconds(60);

    public Duration getMaxInterval() {
        return maxInterval;
    }

    public void setMaxInterval(Duration maxInterval) {
        this.maxInterval = maxInterval;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMinInterval() {
        return minInterval;
    }

    public void setMinInterval(Duration minInterval) {
        this.minInterval = minInterval;
    }

    public Duration getInstanceHeartbeatInterval() {
        return instanceHeartbeatInterval;
    }

    public void setInstanceHeartbeatInterval(Duration instanceHeartbeatInterval) {
        this.instanceHeartbeatInterval = instanceHeartbeatInterval;
    }

    public Duration getInstanceTimeout() {
        return instanceTimeout;
    }

    public void setInstanceTimeout(Duration instanceTimeout) {
        this.instanceTimeout = instanceTimeout;
    }
}
