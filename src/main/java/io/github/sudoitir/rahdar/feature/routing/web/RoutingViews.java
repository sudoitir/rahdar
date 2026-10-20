package io.github.sudoitir.rahdar.feature.routing.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/** The cross-node routing views: diverts and bridges. */
public final class RoutingViews {

    private RoutingViews() {}

    /**
     * One divert, merged across the nodes it was found on.
     *
     * <p>There is no field saying where the divert came from. Artemis records nothing
     * on a divert distinguishing one declared in {@code broker.xml} from one created
     * over management, and offers no way to read configured-but-undeployed diverts,
     * so the system does not claim an origin it cannot establish (ADR-0065 D2).
     *
     * <p>{@code owner} is the one attribution that is honest, because it comes from
     * Rahdar's own records rather than from the broker.
     */
    public record DivertView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String routingName,
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) String forwardingAddress,
            @Schema(nullable = true) String filter,
            @Schema(nullable = true) String routingType,
            @Schema(nullable = true) String transformerClassName,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Exclusive diverts take the message rather than copying it, and Artemis"
                            + " evaluates them before non-exclusive ones. The difference between traffic"
                            + " being duplicated and traffic being taken away.")
            boolean exclusive,

            @Schema(requiredMode = REQUIRED) boolean retroactiveResource,

            @Schema(
                    requiredMode = RequiredMode.NOT_REQUIRED,
                    nullable = true,
                    description = "What in Rahdar owns this divert, when Rahdar's own records say it does:"
                            + " MESSAGE_CAPTURE for a capture tap, OPERATOR for one created through the"
                            + " routing screen. Null means Rahdar has no record of it, which is not a claim"
                            + " that it came from broker configuration.")
            String owner,

            @Schema(
                    requiredMode = RequiredMode.NOT_REQUIRED,
                    nullable = true,
                    description = "The capture subscription this divert serves, when owner is MESSAGE_CAPTURE."
                            + " Such a divert is not deletable from the routing view: reconciliation would"
                            + " reinstate it, so the deletion would appear to succeed and then undo itself.")
            UUID captureSubscriptionId,

            @Schema(
                    requiredMode = RequiredMode.NOT_REQUIRED,
                    nullable = true,
                    description = "The <divert> element that would make broker configuration carry this divert,"
                            + " when owner is OPERATOR: Rahdar created it, so the deployed broker.xml does not.")
            String brokerXml,

            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal,
            @Schema(requiredMode = REQUIRED) List<NodeRef> perNode) {}

    /**
     * One bridge, merged across the nodes it was found on. Read-only, permanently:
     * creating or changing a bridge alters how a cluster is wired to other brokers
     * and is invisible to whatever manages that cluster's configuration.
     */
    public record BridgeView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String queueName,
            @Schema(nullable = true) String forwardingAddress,
            @Schema(nullable = true) String filterString,
            @Schema(nullable = true) String discoveryGroupName,
            @Schema(nullable = true) String transformerClassName,
            @Schema(requiredMode = REQUIRED) List<String> staticConnectors,
            @Schema(requiredMode = REQUIRED) long messagesAcknowledged,
            @Schema(requiredMode = REQUIRED) long messagesPendingAcknowledgement,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Started on at least one node. A bridge started on some nodes and not"
                            + " others is a divergence the operator needs to see, so this is 'any', not"
                            + " 'all' — perNode says which.")
            boolean started,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Connected to its target on at least one node. Started and connected are"
                            + " different facts: a started bridge that cannot reach its target is the state"
                            + " 'is this bridge actually running' is really asking about.")
            boolean connected,

            @Schema(requiredMode = REQUIRED) boolean useDuplicateDetection,
            @Schema(requiredMode = REQUIRED) boolean highlyAvailable,
            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal,
            @Schema(requiredMode = REQUIRED) List<BridgeNodeCell> perNode) {}

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

    /** A node a merged routing row was found on. */
    public record NodeRef(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName) {}

    /** One node's contribution to a bridge row — its running state there. */
    public record BridgeNodeCell(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean started,
            @Schema(requiredMode = REQUIRED) boolean connected,
            @Schema(requiredMode = REQUIRED) long messagesAcknowledged,
            @Schema(requiredMode = REQUIRED) long messagesPendingAcknowledgement) {}
}
