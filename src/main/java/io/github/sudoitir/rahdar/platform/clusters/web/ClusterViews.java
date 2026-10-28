package io.github.sudoitir.rahdar.platform.clusters.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerAccount;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The read side of the cluster API. Every type here is a projection for the
 * browser — <strong>no credential material appears in any of them</strong>.
 *
 * <p>Response fields carry {@code @Schema} so the generated OpenAPI document
 * declares requiredness and nullability honestly (ADR-0019): a field is
 * {@code requiredMode = REQUIRED} unless it is marked {@code nullable = true}.
 * The frontend's {@code schema.d.ts} is generated from this.
 */
public final class ClusterViews {

    private ClusterViews() {}

    /** The broker system a cluster runs. */
    public record SystemView(
            @Schema(requiredMode = REQUIRED, description = "The system's id, such as artemis.")
            String id,

            @Schema(requiredMode = REQUIRED, description = "The system's name for people.")
            String name) {}

    /** One kind of resource the cluster's system has, and what can be done to it. */
    public record KindView(
            @Schema(requiredMode = REQUIRED, description = "The kind's id, such as artemis.queue.")
            String id,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The role the kind plays: SUBSCRIPTION, DESTINATION, ROUTE, CONSUMER and so on.")
            String resourceClass,

            @Schema(requiredMode = REQUIRED, description = "The kind's name for people, singular and plural.")
            KindName label,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What the system can do to this kind (create, delete, pause, purge, ...), plus"
                            + " what Rahdar adds when the system provides it (reset-counters, expire).")
            List<String> verbs) {}

    /** One row in the cluster rail: rolled-up health, no detail. */
    public record ClusterSummary(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) ClusterHealth.Level health,
            @Schema(requiredMode = REQUIRED) int nodeCount,
            @Schema(requiredMode = REQUIRED) Instant updatedAt,
            @Schema(nullable = true) UUID environmentId,
            @Schema(requiredMode = REQUIRED) SystemView system,
            @Schema(requiredMode = REQUIRED) List<KindView> kinds) {}

    /** The full cluster screen payload. */
    public record ClusterDetail(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) TopologyView topology,
            @Schema(requiredMode = REQUIRED) CapabilitiesView capabilities,
            @Schema(requiredMode = REQUIRED) HealthView health,
            @Schema(nullable = true) UUID environmentId,

            @Schema(
                    nullable = true,
                    description = "How Rahdar reaches the cluster. Absent for a caller who sees the cluster only"
                            + " through a team's subscriptions")
            ClusterConnectionDetail connection,

            @Schema(requiredMode = REQUIRED) SystemView system,
            @Schema(requiredMode = REQUIRED) List<KindView> kinds) {}

    /** {@code PATCH /clusters/{id}}: the cluster as the edit left it. */
    public record ClusterConnectionView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String description,
            @Schema(requiredMode = REQUIRED) ClusterConnectionDetail connection) {}

    /**
     * What an operator may edit about a cluster's connection ({@code PATCH /clusters/{id}}), minus both
     * passwords, which are never returned.
     */
    public record ClusterConnectionDetail(
            @Schema(nullable = true) String managementUrlPattern,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The management URLs the operator gave as seeds, from the nodes that hold one")
            List<String> seedUrls,

            @Schema(nullable = true) String tlsBundle,
            @Schema(nullable = true) String managementUsername,

            @Schema(
                    nullable = true,
                    description = "The Core account's user name, or null while Core uses the management account")
            String coreUsername) {}

    /** One broker endpoint — a {@code broker_node} row, minus anything secret. */
    public record NodeEndpointView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String artemisNodeId,
            @Schema(nullable = true) String jolokiaUrl,
            @Schema(nullable = true) String coreUrl,
            @Schema(requiredMode = REQUIRED) String haRole,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(nullable = true) Boolean replicaSync,
            @Schema(nullable = true) String version,

            @Schema(requiredMode = REQUIRED, description = "Where version sits against the supported Artemis range")
            BrokerVersion.Support versionSupport,

            @Schema(nullable = true) String lastError,
            @Schema(nullable = true) BrokerConnectionException.Kind lastErrorKind,
            @Schema(nullable = true) Instant lastSeenAt,

            @Schema(nullable = true, description = "Where the management URL came from; null while there is none")
            ManagementUrlSource urlSource,

            @Schema(nullable = true, description = "Why the node has no management URL")
            ManagementUrlProblem urlProblem,

            @Schema(requiredMode = REQUIRED, description = "Whether an operator set the Core URL")
            boolean coreUrlManual,

            @Schema(requiredMode = REQUIRED) boolean manageable) {

        /** The endpoint without where it is reached or what went wrong reaching it. */
        public NodeEndpointView withoutConnectionDetails() {
            return new NodeEndpointView(
                    id,
                    name,
                    artemisNodeId,
                    null,
                    null,
                    haRole,
                    state,
                    active,
                    replicaSync,
                    version,
                    versionSupport,
                    null,
                    null,
                    lastSeenAt,
                    urlSource,
                    urlProblem,
                    coreUrlManual,
                    manageable);
        }
    }

    /** An HA pair (or standalone) keyed by NodeID. */
    public record LogicalNodeView(
            @Schema(nullable = true) String artemisNodeId,
            @Schema(requiredMode = REQUIRED) String splitBrain,
            @Schema(requiredMode = REQUIRED) boolean replicationBehind,
            @Schema(requiredMode = REQUIRED) List<NodeEndpointView> endpoints) {

        public LogicalNodeView withoutConnectionDetails() {
            return new LogicalNodeView(
                    artemisNodeId,
                    splitBrain,
                    replicationBehind,
                    endpoints.stream()
                            .map(NodeEndpointView::withoutConnectionDetails)
                            .toList());
        }
    }

    /** {@code GET /clusters/{id}/topology}. */
    public record TopologyView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) List<LogicalNodeView> nodes) {

        /** The topology without the management and Core URLs of its nodes, or their last errors. */
        public TopologyView withoutConnectionDetails() {
            return new TopologyView(
                    clusterId,
                    nodes.stream()
                            .map(LogicalNodeView::withoutConnectionDetails)
                            .toList());
        }
    }

    /** One capability's Phase 1 assessment. */
    public record CapabilityView(
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) String reason,
            @Schema(nullable = true) String brokerXmlSnippet) {}

    /** {@code GET /clusters/{id}/capabilities}. */
    public record CapabilitiesView(
            @Schema(requiredMode = REQUIRED) CapabilityView managementRead,
            @Schema(requiredMode = REQUIRED) CapabilityView managementWrite,
            @Schema(requiredMode = REQUIRED) CapabilityView notifications,
            @Schema(requiredMode = REQUIRED) CapabilityView messageIo,
            @Schema(requiredMode = REQUIRED) CapabilityView slowConsumerDetection,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Operations that need a newer Artemis release than some node runs")
            List<VersionGateView> versionGates) {

        /** The statuses alone: the reasons and advice name hosts, URLs and broker configuration. */
        public CapabilitiesView withoutConnectionDetails() {
            return new CapabilitiesView(
                    bare(managementRead),
                    bare(managementWrite),
                    bare(notifications),
                    bare(messageIo),
                    bare(slowConsumerDetection),
                    versionGates);
        }

        private static CapabilityView bare(CapabilityView capability) {
            return new CapabilityView(capability.status(), "", null);
        }
    }

    /**
     * One version-gated operation, shaped like a {@link CapabilityView} so a control
     * gates on it the same way: unavailable when no node runs a release that has it.
     */
    public record VersionGateView(
            @Schema(requiredMode = REQUIRED) String feature,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String requiredVersion,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) String reason,
            @Schema(nullable = true) String brokerXmlSnippet,
            @Schema(requiredMode = REQUIRED) List<NodeVersionView> nodes) {}

    /** Whether one node's release has a version-gated operation. */
    public record NodeVersionView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String version,
            @Schema(requiredMode = REQUIRED) boolean supported) {}

    /** {@code GET /clusters/{id}/health}. */
    public record HealthView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) String level,
            @Schema(requiredMode = REQUIRED) List<String> liveEndpointNames,
            @Schema(requiredMode = REQUIRED) String splitBrain,
            @Schema(requiredMode = REQUIRED) boolean replicationBehind,
            @Schema(requiredMode = REQUIRED) List<String> notes,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The accounts a broker rejected, and on which nodes, so the view can link to"
                            + " where the account is edited")
            List<CredentialRejectionView> credentialRejections) {}

    /** One account the brokers refused, with the nodes that refused it. */
    public record CredentialRejectionView(
            @Schema(requiredMode = REQUIRED) BrokerAccount account,
            @Schema(requiredMode = REQUIRED) List<String> nodeNames) {}

    /** One node as a connection check found it: its address, and what each account did there. */
    public record NodeProbeView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String haRole,
            @Schema(nullable = true) String artemisNodeId,
            @Schema(nullable = true) String version,
            @Schema(nullable = true) String managementUrl,
            @Schema(nullable = true) ManagementUrlSource urlSource,
            @Schema(nullable = true) ManagementUrlProblem urlProblem,
            @Schema(requiredMode = REQUIRED) AccountResult management,
            @Schema(requiredMode = REQUIRED) AccountResult core) {}

    /** How many entries of each section adopting the running configuration would declare. */
    public record AdoptionCountsView(
            @Schema(requiredMode = REQUIRED) int addresses,
            @Schema(requiredMode = REQUIRED) int addressSettings,
            @Schema(requiredMode = REQUIRED) int securitySettings,
            @Schema(requiredMode = REQUIRED) int diverts) {}

    /** What adopting the running configuration at registration would declare, for review. */
    public record AdoptionPreviewView(
            @Schema(requiredMode = REQUIRED) AdoptionCountsView counts,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Items the live nodes report differently, each naming the nodes and their values")
            List<String> disagreements,

            @Schema(requiredMode = REQUIRED) List<String> notes) {}

    /** {@code PATCH /clusters/{id}?dryRun=true} — what the edited connection would find, nothing saved. */
    public record ConnectionCheck(
            @Schema(requiredMode = REQUIRED) String managementUrlPattern,
            @Schema(requiredMode = REQUIRED) List<NodeProbeView> nodes) {}

    /** {@code POST /clusters?dryRun=true} — what a connection check found, nothing saved. */
    public record RegisterPreview(
            @Schema(requiredMode = REQUIRED) CapabilitiesView capabilities,
            @Schema(requiredMode = REQUIRED) int reachableSeeds,
            @Schema(requiredMode = REQUIRED) int discoveredNodes,
            @Schema(requiredMode = REQUIRED) TopologyView topology,

            @Schema(requiredMode = REQUIRED, description = "The pattern the check derived management URLs from")
            String managementUrlPattern,

            @Schema(requiredMode = REQUIRED, description = "One row per node the check found")
            List<NodeProbeView> nodes,

            @Schema(
                    nullable = true,
                    description = "What adopting the running configuration would declare. Absent when the caller"
                            + " may not declare configuration, or the configuration feature is off")
            AdoptionPreviewView adoption,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What each enabled feature adds to the check, keyed by feature id and"
                            + " read from the reachable node. brokerconfig contributes a"
                            + " ConfigRecommendationsView: what to declare once the cluster is registered")
            Map<String, Object> contributions) {}
}
