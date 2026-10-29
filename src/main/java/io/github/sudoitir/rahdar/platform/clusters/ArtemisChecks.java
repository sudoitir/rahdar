package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AccessFinding;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AdoptionPreviewView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ArtemisNodeDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilityView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.Failure;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeProbeView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeReach;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeVersion;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RegisterPreview;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RegisteredRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the Jolokia check found, laid out as the {@link ConnectionCheck} every system answers with (ADR-0219): its
 * per-node probes become nodes with their reach, its capabilities become access findings, and its adoption preview
 * and feature contributions ride along.
 */
final class ArtemisChecks {

    private static final String ARTEMIS = "artemis";

    private ArtemisChecks() {}

    /** A registration check that reached the cluster. */
    static ConnectionCheck of(RegisterPreview preview) {
        return build(
                preview.nodes(),
                access(preview.capabilities()),
                null,
                preview.adoption(),
                preview.managementUrlPattern(),
                preview.contributions());
    }

    /** An edit's check: the nodes the edited connection would find. */
    static ConnectionCheck of(String managementUrlPattern, List<NodeProbeView> nodes) {
        return build(nodes, List.of(), null, null, managementUrlPattern, Map.of());
    }

    /** A check that ran and could not reach a broker: the finding, not a refusal of the request. */
    static ConnectionCheck failed(BrokerConnectionException.Kind kind, String detail) {
        String cause =
                switch (kind) {
                    case CREDENTIALS_REJECTED -> "AUTHENTICATION";
                    case TLS_FAILED -> "TLS";
                    case UNSUPPORTED_VERSION -> "UNSUPPORTED_VERSION";
                    case NOT_ARTEMIS, WRONG_PATH, BAD_RESPONSE -> "REFUSED";
                    case UNREACHABLE, THROTTLED -> "TIMEOUT";
                };
        String next =
                switch (cause) {
                    case "AUTHENTICATION" -> "Check the management account's user name and password.";
                    case "TLS" ->
                        "Check the TLS bundle trusts the broker's certificate and that the host name matches it.";
                    case "UNSUPPORTED_VERSION" -> "Register a broker that runs a supported Artemis release.";
                    case "REFUSED" ->
                        "Check the seed is a Jolokia address of an Artemis broker, such as .../console/jolokia.";
                    default ->
                        "Check the seed's host and port are reachable from Rahdar and that a firewall is not dropping the traffic.";
                };
        return build(List.of(), List.of(), new Failure(cause, detail, next), null, null, Map.of());
    }

    /** The check of brokers a registered cluster already holds: it ran, and says whose they are when the caller may know. */
    static ConnectionCheck alreadyRegistered(ClusterAlreadyRegisteredException held) {
        ConnectionCheck base = build(List.of(), List.of(), null, null, null, Map.of());
        return held.existingClusterId() == null
                ? base
                : base.withAlreadyRegistered(new RegisteredRef(held.existingClusterId(), held.existingClusterName()));
    }

    private static ConnectionCheck build(
            List<NodeProbeView> probes,
            List<AccessFinding> access,
            Failure failure,
            AdoptionPreviewView adoption,
            String pattern,
            Map<String, Object> contributions) {
        List<NodeReach> nodes = probes.stream().map(ArtemisChecks::node).toList();
        String version = probes.stream()
                .map(NodeProbeView::version)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new ConnectionCheck(
                ARTEMIS,
                version == null
                        ? null
                        : new io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.VersionFound(
                                version, null, true),
                failure == null || !"UNSUPPORTED_VERSION".equals(failure.cause()),
                null,
                null,
                nodes,
                null,
                access,
                failure,
                adoption,
                pattern,
                contributions);
    }

    private static NodeReach node(NodeProbeView n) {
        boolean ok = n.management() == AccountResult.ACCEPTED;
        String cause = ok ? null : causeOf(n);
        return new NodeReach(
                n.name(),
                n.managementUrl(),
                List.of("BROKER"),
                ok ? "OK" : "UNREACHABLE",
                cause,
                ok ? null : messageOf(n),
                n.version() == null ? null : new NodeVersion(n.version(), BrokerVersion.support(n.version())),
                new ArtemisNodeDetail(
                        n.artemisNodeId(), n.haRole(), n.urlSource(), n.urlProblem(), n.management(), n.core()));
    }

    private static String causeOf(NodeProbeView n) {
        if (n.management() == AccountResult.REJECTED) {
            return "AUTHENTICATION";
        }
        ManagementUrlProblem problem = n.urlProblem();
        if (problem == null) {
            return "TIMEOUT";
        }
        return switch (problem) {
            case CREDENTIALS_REJECTED -> "AUTHENTICATION";
            case TLS_FAILED -> "TLS";
            case UNREACHABLE -> "TIMEOUT";
            case NO_PATTERN, WRONG_ENDPOINT, OTHER_BROKER -> "REFUSED";
        };
    }

    private static String messageOf(NodeProbeView n) {
        if (n.management() == AccountResult.REJECTED) {
            return "The broker refused the management account.";
        }
        if (n.urlProblem() != null) {
            return switch (n.urlProblem()) {
                case NO_PATTERN -> "No management URL pattern to derive this node's address from.";
                case UNREACHABLE -> "Nothing answered at the node's management address.";
                case CREDENTIALS_REJECTED -> "The node refused the management account.";
                case WRONG_ENDPOINT -> "The address did not answer as an Artemis broker's Jolokia agent.";
                case TLS_FAILED -> "The TLS handshake with the node failed.";
                case OTHER_BROKER -> "A different broker answered at the address derived for this node.";
            };
        }
        return "The node was not reached.";
    }

    private static List<AccessFinding> access(CapabilitiesView c) {
        List<AccessFinding> out = new ArrayList<>(List.of(
                finding("management.read", c.managementRead()),
                finding("management.write", c.managementWrite()),
                finding("notifications", c.notifications()),
                finding("messages", c.messageIo()),
                finding("slow-consumer-detection", c.slowConsumerDetection())));
        c.versionGates()
                .forEach(g -> out.add(new AccessFinding(
                        "version-gate:" + g.feature(),
                        g.status(),
                        g.label() + " needs Artemis " + g.requiredVersion() + " or later. " + g.reason(),
                        g.brokerXmlSnippet(),
                        null)));
        return out;
    }

    private static AccessFinding finding(String id, CapabilityView view) {
        return new AccessFinding(id, view.status(), view.reason(), view.brokerXmlSnippet(), null);
    }
}
