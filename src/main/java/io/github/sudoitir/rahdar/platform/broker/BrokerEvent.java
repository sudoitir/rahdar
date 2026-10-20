package io.github.sudoitir.rahdar.platform.broker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something that happened on a broker, as the broker SDK reports it (ADR-0026). {@code type} is one of
 * the SDK's standard event types ({@code openbroker.consumer.attached}) or a system's own, namespaced
 * ({@code artemis.message_acknowledged}). The promoted fields name what the event involves, by name,
 * wherever the broker says it; {@link #data} keeps everything the broker said.
 */
public record BrokerEvent(
        UUID clusterId,
        UUID nodeId,
        String type,
        Instant occurredAt,
        String destination,
        String subscription,
        String consumer,
        String channel,
        String connection,
        String remoteAddress,
        String principal,
        Map<String, Object> data) {}
