package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.BrokerSystem;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.broker.MessageExpiry;
import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.KindView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.SystemView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** What system a cluster runs and what kinds of resource it has, for the views that tell a client what to offer. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterKinds {

    private final BrokerSystems systems;
    private final BrokerGateway gateway;
    private final SystemExtensions extensions;
    private final ClusterDirectory nodes;

    /** Whether the cluster's kind of this id reads a partitioned log, so its messages are browsed by partition. */
    public boolean partitioned(UUID clusterId, String kind) {
        return kindsOf(clusterId).stream().anyMatch(k -> k.id().equals(kind) && k.partitioned());
    }

    /**
     * Whether the cluster is read and changed as one: it has nodes, and none of them has a management URL, so its
     * driver answers for the cluster as a whole (Kafka, RabbitMQ) and there is no node to fan out to (ADR-0224).
     * An Artemis cluster is not, and a cluster with no stored node is not either.
     */
    public boolean readAsOne(UUID clusterId) {
        List<ClusterNode> stored = nodes.nodes(clusterId);
        return !stored.isEmpty() && stored.stream().noneMatch(n -> n.getManagementUrl() != null);
    }

    /** The ids of the cluster's kinds that are subscriptions, which messages are received from. */
    public List<String> subscriptionKinds(UUID clusterId) {
        return kindsOf(clusterId).stream()
                .filter(k -> ResourceClass.SUBSCRIPTION.name().equals(k.resourceClass()))
                .map(KindView::id)
                .toList();
    }

    /** The id of the system the cluster runs. */
    public String systemId(UUID clusterId) {
        return systems.idOf(clusterId);
    }

    /** The name of the system the cluster runs, for messages to people. */
    public String systemName(UUID clusterId) {
        return systemOf(clusterId).name();
    }

    public SystemView systemOf(UUID clusterId) {
        String id = systems.idOf(clusterId);
        return new SystemView(id, systems.of(clusterId).map(BrokerSystem::name).orElse(id));
    }

    /**
     * The cluster's kinds, each with the verbs the system supports and those Rahdar adds for it. A cluster whose
     * system has no driver here lists none, rather than failing the listing of every cluster.
     */
    public List<KindView> kindsOf(UUID clusterId) {
        boolean resetCounters =
                extensions.forCluster(clusterId, QueueCounters.class).isPresent();
        boolean expire = extensions.forCluster(clusterId, MessageExpiry.class).isPresent();
        try {
            List<Kind> all = gateway.cluster(clusterId).resources().kinds();
            Set<KindId> logs = all.stream()
                    .filter(k -> k.resourceClass() == ResourceClass.PARTITION)
                    .flatMap(k -> k.parent().stream())
                    .collect(Collectors.toSet());
            return all.stream()
                    .map(kind -> new KindView(
                            kind.id().value(),
                            kind.resourceClass().name(),
                            ResourceIds.name(kind.label()),
                            logs.contains(kind.id())
                                    || (!logs.isEmpty() && kind.resourceClass() == ResourceClass.SUBSCRIPTION),
                            verbs(kind, resetCounters, expire)))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Cluster {} lists no resource kinds: {}", clusterId, e.toString());
            return List.of();
        }
    }

    private static List<String> verbs(Kind kind, boolean resetCounters, boolean expire) {
        List<String> verbs = new ArrayList<>(kind.verbs().stream()
                .map(v -> v.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .sorted()
                .toList());
        if (kind.resourceClass() == ResourceClass.SUBSCRIPTION) {
            if (resetCounters) {
                verbs.add("reset-counters");
            }
            if (expire) {
                verbs.add("expire");
            }
        }
        return verbs;
    }
}
