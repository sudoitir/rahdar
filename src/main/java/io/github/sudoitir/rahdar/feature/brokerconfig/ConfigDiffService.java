package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigDiff.Entry;
import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigReader.NodeConfig;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigDiffView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigKeyView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigNodeValueView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigNodeView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigSectionView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigSummaryView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigValueGroupView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.NodeConfigEntryView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.NodeConfigSectionView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.NodeConfigView;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Compares every node of a cluster's broker configuration against the others (ADR-0043,
 * ADR-0178). Drift between nodes — a different {@code journal-directory}, a missing
 * {@code security-setting}, a {@code max-size-bytes} one node enforces differently — is silent
 * until failover, when it is expensive.
 *
 * <p>Read-only: no audit event, matching the rule that only mutating calls audit.
 * Each node costs exactly one batched Jolokia POST taken through the per-node rate
 * limiter (non-negotiable #1), following {@code DlqService}.
 *
 * <p>A node that cannot be read is listed with its classified reason and left out of every
 * majority and every state: its absent keys would otherwise all read as removals, a
 * catastrophic-looking report of a connection problem. When fewer than two nodes answer, there
 * is no comparison at all.
 */
@Service
@RequiredArgsConstructor
public class ConfigDiffService {

    private static final String MATCH = "match";

    private final ClusterDirectory brokerNodes;
    private final QueueSnapshots queueSnapshots;
    private final BrokerConnections connections;
    private final ConfigReader reader;
    private final ClusterAccessGuard clusterAccess;
    private final ArtemisNodes artemisNodes;

    /**
     * @param only narrows the comparison to these nodes; {@code null} or empty compares every
     *     node of the cluster
     */
    public ConfigDiffView compare(UUID clusterId, Set<UUID> only) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<ClusterNode> nodes = selected(brokerNodes.nodes(clusterId), only);
        List<String> matches = ConfigReader.matchesFor(addressesOf(clusterId));

        List<Read> reads = nodes.stream().map(n -> read(clusterId, n, matches)).toList();
        List<Integer> answered = answeredIndexes(reads);
        Set<UUID> reduced = reducedNodes(nodes, reads, answered);
        List<ConfigNodeView> nodeViews = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeViews.add(nodeView(
                    nodes.get(i), reads.get(i), reduced.contains(nodes.get(i).getId())));
        }

        List<String> notes = new ArrayList<>();
        if (answered.size() < 2) {
            notes.add("Fewer than two nodes answered, so no comparison could be made. Each node that"
                    + " did not answer is listed with its reason.");
            return new ConfigDiffView(
                    clusterId,
                    List.copyOf(nodeViews),
                    false,
                    List.of(),
                    new ConfigSummaryView(0, 0, 0),
                    0,
                    matches.size(),
                    List.copyOf(notes));
        }

        List<ConfigDiff.Side> broker = new ArrayList<>();
        List<ConfigDiff.Side> addressSettings = new ArrayList<>();
        List<ConfigDiff.Side> securitySettings = new ArrayList<>();
        List<ConfigDiff.Side> acceptors = new ArrayList<>();
        int compared = 0;
        for (int i : answered) {
            ClusterNode node = nodes.get(i);
            NodeConfig config = reads.get(i).config();
            compared = config.matchesCompared();
            broker.add(brokerSide(node, config, reduced.contains(node.getId())));
            addressSettings.add(side(node, ConfigDiff.flattenKeyed(config.addressSettings(), MATCH)));
            securitySettings.add(side(node, ConfigDiff.flattenKeyed(config.securitySettings(), "name")));
            acceptors.add(side(node, ConfigDiff.flattenKeyed(config.acceptors(), "name")));
        }

        for (int i : answered) {
            if (reduced.contains(nodes.get(i).getId())) {
                notes.add(nodes.get(i).getName() + " is a passive backup with a reduced management surface,"
                        + " so it contributes only the keys it exposes; its unexposed attributes are not"
                        + " reported as missing.");
            }
        }
        if (compared < matches.size()) {
            notes.add("Compared " + compared + " of " + matches.size() + " address settings (the default match"
                    + " \"#\" is always included).");
        }

        List<ConfigSectionView> sections = List.of(
                section(ConfigDiff.SECTION_BROKER, broker),
                section(ConfigDiff.SECTION_ADDRESS_SETTINGS, addressSettings),
                section(ConfigDiff.SECTION_SECURITY_SETTINGS, securitySettings),
                section(ConfigDiff.SECTION_ACCEPTORS, acceptors));

        return new ConfigDiffView(
                clusterId,
                List.copyOf(nodeViews),
                true,
                sections,
                summary(sections),
                compared,
                matches.size(),
                List.copyOf(notes));
    }

    private static List<Integer> answeredIndexes(List<Read> reads) {
        List<Integer> answered = new ArrayList<>();
        for (int i = 0; i < reads.size(); i++) {
            if (reads.get(i).config() != null) {
                answered.add(i);
            }
        }
        return answered;
    }

    /**
     * The nodes that are not serving and expose fewer attributes than the serving ones.
     *
     * <p>A node that is not serving may answer with a reduced surface. On Artemis 2.44 it does
     * not (surface check §14, Q1) — a passive backup exposes the same 90 attributes. This
     * guard is for the broker that behaves otherwise: it contributes the keys it exposes
     * instead of having its unexposed attributes reported as missing configuration.
     */
    private Set<UUID> reducedNodes(List<ClusterNode> nodes, List<Read> reads, List<Integer> answered) {
        Set<String> servingAttributes = new LinkedHashSet<>();
        for (int i : answered) {
            if (reads.get(i).config().active()) {
                servingAttributes.addAll(attributeNames(reads.get(i).config()));
            }
        }
        Set<UUID> reduced = new LinkedHashSet<>();
        for (int i : answered) {
            NodeConfig config = reads.get(i).config();
            if (!config.active() && !attributeNames(config).containsAll(servingAttributes)) {
                reduced.add(nodes.get(i).getId());
            }
        }
        return reduced;
    }

    private ConfigDiff.Side brokerSide(ClusterNode node, NodeConfig config, boolean reduced) {
        Predicate<String> brokerExposes =
                reduced ? key -> attributeNames(config).contains(attributeOf(key)) : key -> true;
        return new ConfigDiff.Side(
                node.getId(), node.getName(), ConfigDiff.flatten(config.brokerAttributes()), brokerExposes);
    }

    private List<ClusterNode> selected(List<ClusterNode> all, Set<UUID> only) {
        if (only == null || only.isEmpty()) {
            return all;
        }
        if (only.size() < 2) {
            throw new IllegalArgumentException("Name at least two nodes to compare, or none to compare them all.");
        }
        Set<UUID> known = all.stream().map(ClusterNode::getId).collect(Collectors.toSet());
        only.stream().filter(id -> !known.contains(id)).findFirst().ifPresent(id -> {
            throw new NotFoundException("node", id);
        });
        return all.stream().filter(n -> only.contains(n.getId())).toList();
    }

    /**
     * One node's effective configuration, read live (the folded-in settings read).
     *
     * <p>The diff answers "do these two nodes agree"; this answers "what is this node
     * actually running with", which is the question an operator — or an agent — asks
     * first, and which had no endpoint at all. Same permission tier as the diff, same
     * rate limiter, and equally unaudited: it mutates nothing.
     *
     * <p>A node that cannot be read comes back {@code available = false} with the
     * reason, never as an empty configuration — an absence presented as a fact is the
     * failure mode this whole feature area exists to avoid.
     */
    public NodeConfigView nodeConfig(UUID clusterId, UUID nodeId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        ClusterNode node = brokerNodes.nodes(clusterId).stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("node", nodeId));

        List<String> matches = ConfigReader.matchesFor(addressesOf(clusterId));
        Read read = read(clusterId, node, matches);
        if (read.config() == null) {
            return new NodeConfigView(
                    clusterId,
                    nodeId,
                    node.getName(),
                    false,
                    Boolean.TRUE.equals(node.getActive()),
                    read.failure(),
                    List.of(),
                    0,
                    matches.size(),
                    null);
        }

        NodeConfig config = read.config();
        List<NodeConfigSectionView> sections = List.of(
                nodeSection(ConfigDiff.SECTION_BROKER, ConfigDiff.flatten(config.brokerAttributes())),
                nodeSection(
                        ConfigDiff.SECTION_ADDRESS_SETTINGS, ConfigDiff.flattenKeyed(config.addressSettings(), MATCH)),
                nodeSection(
                        ConfigDiff.SECTION_SECURITY_SETTINGS,
                        ConfigDiff.flattenKeyed(config.securitySettings(), "name")),
                nodeSection(ConfigDiff.SECTION_ACCEPTORS, ConfigDiff.flattenKeyed(config.acceptors(), "name")));

        String note = config.matchesCompared() < matches.size()
                ? "Read " + config.matchesCompared() + " of " + matches.size() + " address settings (the default"
                        + " match \"#\" is always included)."
                : null;

        return new NodeConfigView(
                clusterId,
                nodeId,
                node.getName(),
                true,
                config.active(),
                null,
                sections,
                config.matchesCompared(),
                matches.size(),
                note);
    }

    private static NodeConfigSectionView nodeSection(String section, Map<String, String> flattened) {
        List<NodeConfigEntryView> entries = flattened.entrySet().stream()
                .map(e -> new NodeConfigEntryView(
                        e.getKey(),
                        e.getValue(),
                        ConfigDiff.classify(section, e.getKey()).name()))
                .toList();
        return new NodeConfigSectionView(section, ConfigDiff.sectionLabel(section), entries);
    }

    private Set<String> addressesOf(UUID clusterId) {
        return queueSnapshots.forCluster(clusterId).stream()
                .map(QueueSnapshot::address)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** One node's read: its configuration, or the classified reason it could not be read. */
    private record Read(NodeConfig config, String kind, String message) {

        static Read answered(NodeConfig config) {
            return new Read(config, null, null);
        }

        static Read failed(String kind, String message) {
            return new Read(null, kind, message);
        }

        /** The reason with its class in front, for the single-node read. */
        String failure() {
            return kind + ": " + message;
        }
    }

    private Read read(UUID clusterId, ClusterNode node, List<String> matches) {
        if (node.getJolokiaUrl() == null) {
            return Read.failed(
                    "NO_MANAGEMENT_URL", "This node has no management URL, so its configuration cannot be read.");
        }
        try {
            ArtemisNode client = artemisNodes.on(clusterId, node.getId());
            return Read.answered(reader.read(client, matches));
        } catch (BrokerConnectionException e) {
            return Read.failed(e.kind().name(), e.getMessage());
        }
    }

    private ConfigNodeView nodeView(ClusterNode node, Read read, boolean reducedSurface) {
        NodeConfig config = read.config();
        return new ConfigNodeView(
                node.getId(),
                node.getName(),
                config != null,
                config != null && config.active(),
                reducedSurface,
                read.kind(),
                read.message());
    }

    private static ConfigDiff.Side side(ClusterNode node, Map<String, String> values) {
        return new ConfigDiff.Side(node.getId(), node.getName(), values, key -> true);
    }

    /** The broker attribute a flattened pointer belongs to: its first segment. */
    private static String attributeOf(String pointer) {
        int end = pointer.indexOf('/', 1);
        String segment = end < 0 ? pointer.substring(1) : pointer.substring(1, end);
        return segment.replace("~1", "/").replace("~0", "~");
    }

    private Set<String> attributeNames(NodeConfig config) {
        JsonNode attributes = config.brokerAttributes();
        if (attributes == null || !attributes.isObject()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        attributes.propertyNames().forEach(names::add);
        return names;
    }

    private static ConfigSectionView section(String section, List<ConfigDiff.Side> sides) {
        List<ConfigKeyView> keys = ConfigDiff.compare(section, sides).stream()
                .map(ConfigDiffService::keyView)
                .toList();
        return new ConfigSectionView(section, ConfigDiff.sectionLabel(section), keys);
    }

    private static ConfigKeyView keyView(Entry e) {
        return new ConfigKeyView(
                e.key(),
                e.state().name(),
                ConfigDiff.stateWord(e.state()),
                e.classification().name(),
                e.isDrift(),
                e.values().stream().map(ConfigDiffService::valueView).toList(),
                e.majority(),
                e.outliers().stream().map(ConfigDiffService::valueView).toList(),
                e.groups().stream()
                        .map(g -> new ConfigValueGroupView(
                                g.value(),
                                g.nodes().stream()
                                        .map(ConfigDiffService::valueView)
                                        .toList()))
                        .toList());
    }

    private static ConfigNodeValueView valueView(ConfigDiff.NodeValue v) {
        return new ConfigNodeValueView(v.nodeId(), v.nodeName(), v.value(), v.missing());
    }

    private static ConfigSummaryView summary(List<ConfigSectionView> sections) {
        int drift = 0;
        int expected = 0;
        Set<UUID> nodesWithDrift = new LinkedHashSet<>();
        for (ConfigSectionView section : sections) {
            for (ConfigKeyView key : section.keys()) {
                if (key.drift()) {
                    drift++;
                    // With no majority every node is party to the disagreement.
                    List<ConfigNodeValueView> involved = key.majority() == null
                            ? key.valueGroups().stream()
                                    .flatMap(g -> g.nodes().stream())
                                    .toList()
                            : key.outliers();
                    involved.forEach(v -> nodesWithDrift.add(v.nodeId()));
                } else if (ConfigDiff.Classification.EXPECTED.name().equals(key.classification())
                        && !ConfigDiff.State.SAME.name().equals(key.state())) {
                    expected++;
                }
            }
        }
        return new ConfigSummaryView(drift, nodesWithDrift.size(), expected);
    }
}
