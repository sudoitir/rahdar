package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.Timeouts;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The broker timeouts as the SDK reads them: a live view of the settings, so a change in Settings
 * applies to the next call without reconnecting a cluster (ADR-0010).
 */
@Component
public class BrokerTimeouts implements Timeouts {

    private volatile Duration connect;
    private volatile Duration call;

    public BrokerTimeouts(BrokerProperties properties) {
        this.connect = properties.connectTimeout();
        this.call = properties.readTimeout();
    }

    @Override
    public Duration connect() {
        return connect;
    }

    @Override
    public Duration call() {
        return call;
    }

    /** {@code SettingsService} calls this when either timeout changes. */
    public void set(Duration connect, Duration call) {
        this.connect = connect;
        this.call = call;
    }
}
