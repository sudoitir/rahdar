package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.resources.CaptureTaps;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether a tap outlives a queue delete, answered the way the reconciler decides it: by the
 * queues its subscription's pattern matches in the scraped snapshot (ADR-0084 D5).
 */
@Component
@RequiredArgsConstructor
class CaptureTapCoverage implements CaptureTaps {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final CaptureAddresses addresses;

    @Override
    public boolean coversWithout(UUID clusterId, String tapName, String address, String queueName) {
        UUID id = CaptureNames.subscriptionOf(tapName);
        return id != null
                && subscriptions
                        .findById(id)
                        .map(s -> addresses.targets(clusterId, s).stream()
                                .anyMatch(t -> address.equals(CaptureAddresses.addressOf(t))
                                        && !queueName.equals(t.queueName())))
                        .orElse(false);
    }
}
