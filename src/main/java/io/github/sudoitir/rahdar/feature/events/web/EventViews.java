package io.github.sudoitir.rahdar.feature.events.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The broker-events history API (ADR-0026, ADR-0028). */
public final class EventViews {

    private EventViews() {}

    public record BrokerEventView(
            @Schema(requiredMode = REQUIRED) long seq,
            @Schema(requiredMode = REQUIRED) Instant occurredAt,
            @Schema(requiredMode = REQUIRED) Instant receivedAt,
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(nullable = true) String destination,
            @Schema(nullable = true) String routingName,
            @Schema(nullable = true) String consumerName,
            @Schema(nullable = true) String sessionName,
            @Schema(nullable = true) String connectionName,
            @Schema(nullable = true) String remoteAddress,
            @Schema(nullable = true) String username,
            @Schema(nullable = true) UUID nodeId,
            @Schema(nullable = true) Map<String, Object> props) {}

    public record BrokerEventPageView(
            @Schema(requiredMode = REQUIRED) List<BrokerEventView> data,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize,

            @Schema(nullable = true, description = "The total; null when the page was reached by cursor.")
            Long count,

            @Schema(requiredMode = REQUIRED) boolean hasNext,

            @Schema(nullable = true, description = "Pass as `after` for the page that follows; null on the last page.")
            String next,

            @Schema(requiredMode = REQUIRED, description = "Read from a replica that may be a little behind.")
            boolean mayLag,

            @Schema(requiredMode = REQUIRED) long dropped,
            @Schema(nullable = true) Instant oldestRetained) {}
}
