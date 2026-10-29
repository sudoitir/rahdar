package io.github.sudoitir.rahdar.platform.scrape;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when a metric series may be stored (ADR-0220). A series is stored at most once per
 * {@link #HEARTBEAT}, and at least that often, whether or not the value changed. The memory is the
 * process that scrapes: a restart stores one sample per series and then holds the gate again.
 */
public final class SampleGate {

    /** How often a series is stored. The chart bucket floor and the rate window use the same duration. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(60);

    private final ConcurrentHashMap<Key, Instant> lastPersisted = new ConcurrentHashMap<>();

    /**
     * The window a rate is read over: two heartbeats, or two tier-B intervals when the scrape is slower than the
     * heartbeat, so a series stored on the heartbeat still has two points and a flat rate stays zero.
     */
    public static Duration rateWindow(Duration tierB) {
        Duration heartbeatWindow = HEARTBEAT.multipliedBy(2);
        Duration tierWindow = tierB.multipliedBy(2);
        return tierWindow.compareTo(heartbeatWindow) > 0 ? tierWindow : heartbeatWindow;
    }

    /** True when this series has not been stored inside the current heartbeat. A clock step backwards stores again. */
    boolean due(String kind, UUID clusterId, UUID nodeId, String subject, String metric, Instant now) {
        Key key = new Key(kind, clusterId, nodeId, subject, metric);
        boolean[] store = {false};
        lastPersisted.compute(key, (ignored, previous) -> {
            if (previous != null
                    && !now.isBefore(previous)
                    && Duration.between(previous, now).compareTo(HEARTBEAT) < 0) {
                store[0] = false;
                return previous;
            }
            store[0] = true;
            return now;
        });
        return store[0];
    }

    private record Key(String kind, UUID clusterId, UUID nodeId, String subject, String metric) {}
}
