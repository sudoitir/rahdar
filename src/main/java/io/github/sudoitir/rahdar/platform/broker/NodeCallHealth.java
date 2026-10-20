package io.github.sudoitir.rahdar.platform.broker;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The outcome of the latest management calls to each broker endpoint, keyed by its Jolokia
 * URL (operational-health spec). Recorded as a side effect of every call; reading it never
 * makes one.
 */
@Component
public class NodeCallHealth {

    /** The latest success and failure of calls to one endpoint. */
    public record Calls(Instant lastSuccess, Instant lastFailure, String lastError) {

        /** Whether the most recent outcome was a failure. */
        public boolean failing() {
            return lastFailure != null && (lastSuccess == null || lastFailure.isAfter(lastSuccess));
        }
    }

    private final Map<String, Calls> byUrl = new ConcurrentHashMap<>();

    public void succeeded(String managementUrl) {
        Instant now = Instant.now();
        byUrl.merge(
                managementUrl,
                new Calls(now, null, null),
                (old, fresh) -> new Calls(now, old.lastFailure(), old.lastError()));
    }

    public void failed(String managementUrl, String error) {
        Instant now = Instant.now();
        byUrl.merge(
                managementUrl, new Calls(null, now, error), (old, fresh) -> new Calls(old.lastSuccess(), now, error));
    }

    public Optional<Calls> calls(String managementUrl) {
        return Optional.ofNullable(byUrl.get(managementUrl));
    }
}
