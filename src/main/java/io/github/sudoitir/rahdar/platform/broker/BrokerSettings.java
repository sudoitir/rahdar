package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.settings.SettingDef;
import io.github.sudoitir.rahdar.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.rahdar.kernel.settings.SettingsContribution;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The per-node call ceiling, Jolokia timeouts and the bulk safety caps (ADR-0010, ADR-0022, ADR-0093). */
@Component
@RequiredArgsConstructor
public class BrokerSettings implements SettingsContribution {

    public static final String RATE_LIMIT = "rate-limit.calls-per-second";
    public static final String CONNECT_TIMEOUT = "broker.connect-timeout";
    public static final String READ_TIMEOUT = "broker.read-timeout";
    /** Server-enforced ceiling on one destructive message operation (ADR-0022). */
    public static final String BULK_CAP = "safety.bulk-cap";
    /** Most queues one bulk run may act on; there is no override (ADR-0093). */
    public static final String BULK_QUEUE_CAP = "safety.bulk-queue-cap";

    private final RateLimitProperties rateLimit;
    private final BrokerProperties broker;
    private final SafetyProperties safety;
    private final NodeCallLimiter limiter;
    private final BrokerTimeouts timeouts;

    @Override
    public String featureId() {
        return "broker";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        RATE_LIMIT,
                        "Scrape",
                        "Per-node call ceiling",
                        "Management calls per second, per broker node. Rahdar must never be the load.",
                        Kind.INT,
                        () -> Integer.toString(rateLimit.managementCallsPerSecond()),
                        s -> limiter.setPermitsPerSecond(s.intValue(RATE_LIMIT))),
                new SettingDef(
                        CONNECT_TIMEOUT,
                        "Broker transport",
                        "Connect timeout",
                        "TCP connect timeout for every Jolokia call. Applies to clients built after the change.",
                        Kind.DURATION,
                        () -> broker.connectTimeout().toString(),
                        this::applyTimeouts),
                new SettingDef(
                        READ_TIMEOUT,
                        "Broker transport",
                        "Read timeout",
                        "Response timeout for every Jolokia call. Raise it for a broker with very large queue sets.",
                        Kind.DURATION,
                        () -> broker.readTimeout().toString(),
                        this::applyTimeouts),
                new SettingDef(
                        BULK_CAP,
                        "Safety",
                        "Bulk operation cap",
                        "Most messages one destructive operation may touch before it needs an explicit override.",
                        Kind.INT,
                        () -> Integer.toString(safety.bulkCap()),
                        null),
                new SettingDef(
                        BULK_QUEUE_CAP,
                        "Safety",
                        "Bulk run queue cap",
                        "Most queues one bulk run may act on. There is no override: narrow the selection instead.",
                        Kind.INT,
                        () -> Integer.toString(safety.bulkQueueCap()),
                        null));
    }

    private void applyTimeouts(SettingsService s) {
        timeouts.set(s.duration(CONNECT_TIMEOUT), s.duration(READ_TIMEOUT));
    }
}
