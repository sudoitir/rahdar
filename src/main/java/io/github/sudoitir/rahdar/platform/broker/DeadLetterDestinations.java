package io.github.sudoitir.rahdar.platform.broker;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a cluster's undeliverable and expired messages go by default, as its brokers are configured.
 * Systems keep this in different places, so each system's module provides it.
 */
public interface DeadLetterDestinations extends SystemExtension {

    /**
     * The default dead-letter and expiry destinations, each named with its kind ({@code dead-letter} or
     * {@code expiry}); empty when the node's configuration cannot be read.
     */
    Optional<Map<String, String>> defaults(UUID clusterId, UUID nodeId);
}
