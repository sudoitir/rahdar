package io.github.sudoitir.rahdar.feature.routing.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The cross-node routing views: diverts and bridges. */
public final class RoutingViews {

    private RoutingViews() {}

    /**
     * One route, merged across the nodes it was found on: a divert ({@code FORWARD}, an address copied or
     * moved into another) or a bridge ({@code LINK}, a queue forwarded to another broker).
     *
     * <p>There is no field saying where a divert came from. Artemis records nothing on a divert
     * distinguishing one declared in {@code broker.xml} from one created over management, and offers no way
     * to read configured-but-undeployed diverts, so the system does not claim an origin it cannot establish
     * (ADR-0065 D2). {@code owner} is the one attribution that is honest, because it comes from Rahdar's own
     * records rather than from the broker.
     *
     * <p>Bridges are read-only, permanently: creating or changing one alters how a cluster is wired to other
     * brokers and is invisible to whatever manages that cluster's configuration.
     */
    public record RouteView(
            @Schema(requiredMode = REQUIRED, description = "The route's canonical id, such as artemis.divert/orders.")
            String id,

            @Schema(requiredMode = REQUIRED, description = "The id of the route's kind, such as artemis.divert.")
            String kind,

            @Schema(requiredMode = REQUIRED, description = "The kind's name for people, singular and plural.")
            KindName kindLabel,

            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"BINDING", "FORWARD", "LINK"},
                    description = "BINDING attaches a subscription to a destination, FORWARD copies or moves"
                            + " what reaches one destination into another, LINK carries it to another broker.")
            String routeClass,

            @Schema(nullable = true, description = "The id of the destination or subscription the route reads from.")
            String source,

            @Schema(nullable = true, description = "The id of the destination the route writes to, when it is one.")
            String target,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What the system calls the route's own properties: its filter, whether it is"
                            + " exclusive, its transformer, whether it is started, and so on.")
            Map<String, String> nativeAttributes,

            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal,
            @Schema(requiredMode = REQUIRED) List<RouteNodeCell> perNode,

            @Schema(
                    requiredMode = RequiredMode.NOT_REQUIRED,
                    nullable = true,
                    description = "What in Rahdar owns this route, when Rahdar's own records say it does:"
                            + " MESSAGE_CAPTURE for a capture tap, OPERATOR for one created through the"
                            + " routing screen. Null means Rahdar has no record of it, which is not a claim"
                            + " that it came from broker configuration.")
            String owner,

            @Schema(
                    requiredMode = RequiredMode.NOT_REQUIRED,
                    nullable = true,
                    description = "The broker.xml element that would make broker configuration carry this"
                            + " route, when owner is OPERATOR: Rahdar created it, so the deployed broker.xml"
                            + " does not.")
            String drift) {}

    /**
     * The result of creating a divert, with the {@code broker.xml} that would make
     * the broker's configuration carry it.
     *
     * <p>The configuration travels with the outcome rather than behind a second
     * request so it cannot be skipped past: the preview that states the blast radius
     * is the same response that states the drift and how to close it.
     */
    public record DivertMutationView(
            @Schema(requiredMode = REQUIRED) LifecycleViews.LifecycleOutcomeView outcome,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The <divert> element that would make this broker's own configuration carry"
                            + " the divert. A divert created over management persists across restarts but is"
                            + " absent from configuration, and this is what closes that gap.")
            String brokerXml) {}

    /** One node's contribution to a route row. */
    public record RouteNodeCell(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(nullable = true, description = "Whether the route is started there; null for a kind with no state.")
            Boolean started,

            @Schema(nullable = true, description = "Whether the route is connected to its target there.")
            Boolean connected,

            @Schema(nullable = true) Long messagesAcknowledged,
            @Schema(nullable = true) Long messagesPendingAcknowledgement) {}
}
