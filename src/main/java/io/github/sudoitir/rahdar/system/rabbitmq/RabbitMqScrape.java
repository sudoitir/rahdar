package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.Placement;
import io.github.sudoitir.openbroker.model.PlacementRole;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ClusterScrape;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionEntity;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A RabbitMQ cluster's nodes on the scrape schedule: one batched read of every node, then each stored node takes its
 * state from what the cluster says of it, and one the cluster did not answer for keeps its last state with the error.
 * The queues are read on the same schedule, which also counts the replicated ones that lost members. A node the
 * cluster stops listing is retired after {@link #RETIRE_AFTER}. A cluster that reports another cluster name than the
 * one registered is not read further, so the two clusters' data is never mixed.
 */
@Component
@RequiredArgsConstructor
class RabbitMqScrape implements ClusterScrape {

    private static final int PAGE = 200;
    private static final KindId STREAM = KindId.of("rabbitmq.stream");
    private static final KindId QUORUM_QUEUE = KindId.of("rabbitmq.quorum-queue");
    private static final KindId CLASSIC_QUEUE = KindId.of("rabbitmq.classic-queue");

    /** Why a stream has no backlog: it keeps its messages until retention removes them. */
    static final String STREAM_HAS_NO_BACKLOG = "A stream keeps its messages until retention removes them; it has no"
            + " backlog. Its size is on the queue's panel.";

    /** A node the cluster has not listed for this long, while the cluster answered, has left it for good. */
    static final Duration RETIRE_AFTER = Duration.ofMinutes(10);

    /** An unchanged health verdict is written again after this long, so readers can tell the scrape is alive. */
    static final Duration HEALTH_HEARTBEAT = Duration.ofSeconds(30);

    /** The AMQP endpoints are probed again only after this long, because a probe waits on every dead endpoint. */
    static final Duration DATA_PLANE_PROBE = Duration.ofSeconds(30);

    private final BrokerGateway gateway;
    private final ClusterDirectory clusters;
    private final NodeStateRecorder recorder;
    private final MeterRegistry meters;
    private final AuditService audit;
    private final ActorResolver actors;
    private final Clock clock;
    private final RabbitMqHealthStore healthStore;
    private final RabbitMqConnectionRepository connections;
    private final AmqpProbe amqpProbe;

    /** What the scrape last wrote of each cluster's health and when, so an unchanged verdict is rewritten only now and then. */
    private final Map<UUID, Written> written = new ConcurrentHashMap<>();

    private record Written(ClusterHealth health, Instant at) {}

    /** Since when each stored node the cluster does not list has been missing, by cluster. */
    private final Map<UUID, Map<UUID, Instant>> unlistedSince = new ConcurrentHashMap<>();

    /** What the last pass over each cluster's queues found, for the next node verdict. */
    private final Map<UUID, RabbitMqHealth.Queues> queues = new ConcurrentHashMap<>();

    /** Why the AMQP endpoints could not be reached when last probed (empty when they could), and when. */
    private final Map<UUID, Probed> dataPlane = new ConcurrentHashMap<>();

    private record Probed(Optional<String> unreachable, Instant at) {}

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    @Override
    public void nodes(UUID clusterId, long cycle) {
        List<ClusterNode> stored = clusters.nodes(clusterId);
        Partial<List<NodeDescription>> described;
        try {
            round(clusterId, "nodes");
            described = gateway.cluster(clusterId).nodes().describe(Scope.whole());
        } catch (BrokerException failure) {
            throw outage(clusterId, stored, failure);
        }
        if (replaced(clusterId, stored, described)) {
            return;
        }
        Map<String, ClusterNode> byNode = new HashMap<>();
        stored.forEach(node -> byNode.put(node.getNativeNodeId(), node));
        Set<String> answered = applyKnown(cycle, described, byNode);
        adoptUnstored(clusterId, described, byNode);
        described.failures().forEach((node, failure) -> record(byNode.get(node.value()), failure));
        retireUnlisted(clusterId, stored, described, answered);
        publish(RabbitMqHealth.judge(clusterId, described, queues.get(clusterId), unreachableDataPlane(clusterId)));
    }

    private RuntimeException outage(UUID clusterId, List<ClusterNode> stored, BrokerException failure) {
        RuntimeException e = BrokerFailures.translate(failure);
        BrokerConnectionException.Kind kind = e instanceof BrokerConnectionException bce ? bce.kind() : null;
        if (kind != BrokerConnectionException.Kind.THROTTLED) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            stored.forEach(node -> recorder.recordNodeError(node.getId(), message, kind));
            publish(RabbitMqHealth.unreachable(clusterId, message));
        }
        return e;
    }

    /** A cluster that now reports another name is not read further. */
    private boolean replaced(UUID clusterId, List<ClusterNode> stored, Partial<List<NodeDescription>> described) {
        String live = described.value().stream()
                .flatMap(node -> node.membership().group().stream())
                .findFirst()
                .orElse(null);
        Optional<String> changed = RabbitMqClusterIdentity.change(
                live, clusters.registeredIdentity(clusterId).orElse(null));
        if (changed.isEmpty()) {
            return false;
        }
        stored.forEach(node ->
                recorder.recordNodeError(node.getId(), changed.get(), BrokerConnectionException.Kind.UNREACHABLE));
        publish(RabbitMqHealth.replaced(clusterId, changed.get()));
        return true;
    }

    private Set<String> applyKnown(
            long cycle, Partial<List<NodeDescription>> described, Map<String, ClusterNode> byNode) {
        Set<String> answered = new HashSet<>();
        for (NodeDescription node : described.value()) {
            ClusterNode known = byNode.get(node.nativeId());
            if (known != null && !described.failures().containsKey(node.node())) {
                recorder.applyTierA(known.getId(), node, cycle);
                answered.add(node.nativeId());
            }
        }
        return answered;
    }

    private void adoptUnstored(
            UUID clusterId, Partial<List<NodeDescription>> described, Map<String, ClusterNode> byNode) {
        for (NodeDescription node : described.value()) {
            if (!byNode.containsKey(node.nativeId())) {
                recorder.adopt(clusterId, node.nativeId(), node.nativeId(), RabbitMqRegistrar.dataEndpoints(node));
            }
        }
    }

    private void retireUnlisted(
            UUID clusterId, List<ClusterNode> stored, Partial<List<NodeDescription>> described, Set<String> answered) {
        List<ClusterNode> unlisted = stored.stream()
                .filter(node -> node.getNativeNodeId() != null && !answered.contains(node.getNativeNodeId()))
                .filter(node -> !described.failures().containsKey(NodeId.of(node.getNativeNodeId())))
                .toList();
        Map<UUID, Instant> missing = unlistedSince.computeIfAbsent(clusterId, id -> new ConcurrentHashMap<>());
        missing.keySet().retainAll(unlisted.stream().map(ClusterNode::getId).toList());
        Instant now = clock.instant();
        for (ClusterNode node : unlisted) {
            Instant since = missing.computeIfAbsent(node.getId(), id -> now);
            if (described.value().isEmpty() || Duration.between(since, now).compareTo(RETIRE_AFTER) < 0) {
                recorder.recordNodeError(
                        node.getId(),
                        "The cluster no longer lists this node.",
                        BrokerConnectionException.Kind.UNREACHABLE);
            } else {
                retire(clusterId, node);
            }
        }
    }

    /** Why no AMQP endpoint accepts a connection, empty when one does or the cluster has none to reach. */
    private Optional<String> unreachableDataPlane(UUID clusterId) {
        Instant now = clock.instant();
        Probed last = dataPlane.get(clusterId);
        if (last != null && Duration.between(last.at(), now).compareTo(DATA_PLANE_PROBE) < 0) {
            return last.unreachable();
        }
        List<String> endpoints = connections
                .findById(clusterId)
                .map(RabbitMqConnectionEntity::getAmqpEndpoints)
                .map(entry -> AmqpEndpoints.parse(List.of(entry)))
                .orElse(List.of());
        Optional<String> unreachable = Optional.empty();
        List<String> causes = new ArrayList<>();
        for (String endpoint : endpoints) {
            Optional<String> cause = amqpProbe.unreachable(AmqpEndpoints.hostAndPort(endpoint));
            if (cause.isEmpty()) {
                causes.clear();
                break;
            }
            causes.add(AmqpEndpoints.hostAndPort(endpoint) + " " + cause.get());
        }
        if (!causes.isEmpty()) {
            unreachable = Optional.of(String.join(", ", causes));
        }
        dataPlane.put(clusterId, new Probed(unreachable, now));
        return unreachable;
    }

    /** Writes the verdict where every replica reads it: when it changed, and otherwise every {@link #HEALTH_HEARTBEAT}. */
    private void publish(ClusterHealth health) {
        Instant now = clock.instant();
        Written last = written.get(health.clusterId());
        if (last != null
                && last.health().equals(health)
                && Duration.between(last.at(), now).compareTo(HEALTH_HEARTBEAT) < 0) {
            return;
        }
        healthStore.write(health, now);
        written.put(health.clusterId(), new Written(health, now));
    }

    /** Drops the cluster's per-cluster meters and everything the scrape remembers of it. */
    void forget(UUID clusterId) {
        meters.find("rahdar.rabbitmq.scrape.rounds")
                .tag("cluster", clusterId.toString())
                .meters()
                .forEach(meters::remove);
        unlistedSince.remove(clusterId);
        written.remove(clusterId);
        queues.remove(clusterId);
        dataPlane.remove(clusterId);
    }

    /** Removes a node the cluster dropped, and records that it left. */
    private void retire(UUID clusterId, ClusterNode node) {
        AuditEvent event = audit.begin(
                actors.system(),
                "NODE_LEFT_CLUSTER",
                "NODE",
                node.getName(),
                clusterId,
                null,
                Map.of(
                        "nativeNodeId",
                        node.getNativeNodeId(),
                        "detail",
                        "node " + node.getNativeNodeId() + " left the cluster: the cluster has not listed it for "
                                + RETIRE_AFTER.toMinutes() + " minutes"),
                false);
        try {
            recorder.retire(node.getId());
            unlistedSince.getOrDefault(clusterId, Map.of()).remove(node.getId());
            audit.succeed(event, 1);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /**
     * One call to the cluster's driver, each of which is one batched read of the whole cluster, counted as
     * {@code rahdar.rabbitmq.scrape.rounds} by cluster and call. Set against the cycles it takes, it is the scrape's
     * cost to the nodes: a cycle that grew one round per queue would show here.
     */
    private void round(UUID clusterId, String call) {
        Counter.builder("rahdar.rabbitmq.scrape.rounds")
                .description("Driver reads the RabbitMQ scrape issued, by cluster and call")
                .tag("cluster", clusterId.toString())
                .tag("call", call)
                .register(meters)
                .increment();
    }

    /**
     * Every queue and stream of the cluster, each carried by the stored node that leads or hosts it, else by the
     * first stored node. The same pass counts the replicated queues that lost members for the next node verdict.
     */
    @Override
    public List<QueueRow> subscriptions(UUID clusterId) {
        List<ClusterNode> stored = clusters.nodes(clusterId);
        RabbitMqClusterIdentity.recorded(stored).ifPresent(change -> {
            throw new IllegalStateException(change);
        });
        ClusterNode first = stored.stream()
                .min(Comparator.comparing(ClusterNode::getName))
                .orElseThrow(() -> new NotFoundException("node", clusterId));
        Map<String, ClusterNode> byNode = new HashMap<>();
        stored.forEach(node -> byNode.put(node.getNativeNodeId(), node));
        List<QueueRow> rows = new ArrayList<>();
        Found found = new Found();
        Optional<String> next = Optional.empty();
        try {
            round(clusterId, "subscriptions");
            do {
                round(clusterId, "subscription-pages");
                ResourceQuery query =
                        ResourceQuery.of(ResourceClass.SUBSCRIPTION).limit(PAGE);
                ResourcePage page = gateway.cluster(clusterId)
                        .resources()
                        .read(next.map(query::continueFrom).orElse(query))
                        .orThrow();
                for (Resource queue : page.items()) {
                    rows.add(row(clusterId, queue, carrier(queue, byNode, first)));
                    found.add(queue, rows.getLast().queueName());
                }
                next = page.continueToken();
            } while (next.isPresent());
        } catch (BrokerException failure) {
            throw BrokerFailures.translate(failure);
        }
        queues.put(clusterId, found.queues());
        return rows;
    }

    private static QueueRow row(UUID clusterId, Resource queue, ClusterNode carrier) {
        QueueRow row = QueueRow.of(clusterId, carrier.getId(), queue);
        return STREAM.equals(queue.id().kind()) ? row.withoutBacklog(STREAM_HAS_NO_BACKLOG) : row;
    }

    private static ClusterNode carrier(Resource queue, Map<String, ClusterNode> byNode, ClusterNode first) {
        return queue.placements().stream()
                .filter(p -> p.role() == PlacementRole.LEADER || p.role() == PlacementRole.INSTANCE)
                .map(p -> byNode.get(p.node().value()))
                .filter(node -> node != null)
                .findFirst()
                .orElse(first);
    }

    /** The replicated queues a pass found behind, collected as the pages go by. */
    private static final class Found {

        private final List<String> underReplicated = new ArrayList<>();
        private final List<String> withoutMajority = new ArrayList<>();
        private final List<String> leaderless = new ArrayList<>();
        private int mirrorsBehind;

        void add(Resource queue, String name) {
            KindId kind = queue.id().kind();
            if (CLASSIC_QUEUE.equals(kind)) {
                if (queue.conditions().stream()
                        .anyMatch(c -> ConditionTypes.UNDER_REPLICATED.equals(c.type())
                                && c.status() == ConditionStatus.TRUE)) {
                    mirrorsBehind++;
                }
                return;
            }
            List<Placement> members = queue.placements();
            if (!(QUORUM_QUEUE.equals(kind) || STREAM.equals(kind))
                    || members.isEmpty()
                    || members.stream().anyMatch(p -> p.inSync().isEmpty())) {
                return;
            }
            long online = members.stream().filter(p -> p.inSync().orElse(false)).count();
            boolean leaderOnline = members.stream()
                    .anyMatch(
                            p -> p.role() == PlacementRole.LEADER && p.inSync().orElse(false));
            if (online < members.size() / 2 + 1) {
                withoutMajority.add(name);
            } else if (!leaderOnline) {
                leaderless.add(name);
            } else if (online < members.size()) {
                underReplicated.add(name);
            }
        }

        RabbitMqHealth.Queues queues() {
            return new RabbitMqHealth.Queues(
                    List.copyOf(underReplicated), List.copyOf(withoutMajority), List.copyOf(leaderless), mirrorsBehind);
        }
    }

    private void record(ClusterNode node, BrokerException failure) {
        if (node != null) {
            RuntimeException e = BrokerFailures.translate(failure);
            recorder.recordNodeError(
                    node.getId(),
                    failure.getMessage(),
                    e instanceof BrokerConnectionException bce
                            ? bce.kind()
                            : BrokerConnectionException.Kind.UNREACHABLE);
        }
    }
}
