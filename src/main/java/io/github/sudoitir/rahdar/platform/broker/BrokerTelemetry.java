package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.LinkListener;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerConnectOptions;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the gateway watches a cluster's links with (ADR-0211, ADR-0212): a listener that records link
 * state in {@link BrokerLinks}, joined with the starter's, which counts recoveries and feeds OpenTelemetry.
 * Sends, receives and settlements are observed by the options the starter assembles; they never write
 * trace context into a message ({@code openbroker.tracing.inject-context} is false), because Rahdar
 * moves, retries, relays and transfers messages that producers wrote, and they must arrive with exactly
 * the properties they left with.
 */
@Component
public class BrokerTelemetry {

    private final BrokerLinks links;
    private final OpenBrokerConnectOptions connectOptions;

    public BrokerTelemetry(BrokerLinks links, OpenBrokerConnectOptions connectOptions) {
        this.links = links;
        this.connectOptions = connectOptions;
    }

    /** Names what a link is for, in diagnostics. */
    void label(String linkId, String owner) {
        links.label(linkId, owner);
    }

    /** The listener a cluster's transport is watched with. */
    LinkListener listener(UUID clusterId, String system) {
        LinkListener recorded = links.listener(clusterId);
        LinkListener starter = connectOptions.linkListener(clusterId.toString(), system);
        return event -> {
            recorded.changed(event);
            starter.changed(event);
        };
    }
}
