package io.github.sudoitir.rahdar.platform.broker;

import java.util.Map;
import java.util.UUID;

/**
 * The message read/write surface, extracted from two real implementations
 * (ADR-0002, ADR-0029): {@code JolokiaMessageTransport} over the management
 * channel, {@link CoreMessageTransport} over the Core protocol client. Only
 * browse and send get a Core path — those are where body/property fidelity
 * lives; by-id / by-filter mutations carry no payload and stay on Jolokia
 * ({@code MessageOperations}).
 */
public interface MessageTransport {

    enum Channel {
        JOLOKIA,
        CORE
    }

    /** The channel this transport nominally uses; an individual call may report a different {@code servedBy}. */
    Channel channel();

    /** One page of a queue, plus which channel actually served it (Core can fall back to Jolokia on a deep page). */
    BrowseResult browse(TransportTarget target, int page, int size, String filter);

    /** Enqueue a message; returns the broker-reported id when available. */
    void send(TransportTarget target, SendSpec spec);

    record BrowseResult(BrowsePage page, Channel servedBy) {}

    /** Everything a transport needs to reach one queue on one node. */
    record TransportTarget(
            UUID clusterId,
            UUID nodeId,
            String queueName,
            String address,
            String routingType,
            String jolokiaUrl,
            String coreUrl) {}

    /** A message to enqueue. {@code body} is base64-encoded bytes when {@code kind} is {@link MessageKind#BYTES}. */
    record SendSpec(
            MessageKind kind,
            boolean durable,
            String body,
            Map<String, Object> headers,
            Map<String, Object> properties) {}
}
