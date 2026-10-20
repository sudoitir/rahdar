package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyAcquired;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

/**
 * The tiered scrape scheduler (ADR-0015). Retires {@code HaRefreshTask}.
 *
 * <ul>
 *   <li><b>Tier A</b> (~5s): one HA-attribute read per manageable node, each on its own
 *       so a stalled node delays only itself, then a per-cluster split-brain corroboration
 *       pass. It is the only tier that decides whether a node is reachable.
 *   <li><b>Tier B</b> (~15s): the first {@code listQueues} page per node, so the
 *       busiest queues get a fast refresh without waiting for the full sweep.
 *   <li><b>Tier C</b> (~5m): every {@code listQueues} page per node, one after the
 *       other in the same tick, then reaping rows the sweep did not touch, so a
 *       node is fully known one tick after it is registered.
 *   <li><b>Discovery</b> (~1m): re-run topology discovery per cluster, so a broker that
 *       joined after registration appears without anyone asking (ADR-0119).
 * </ul>
 *
 * <p>Every tier: acquire a per-node permit, do the POST, parse, hand a plain
 * result to a short transaction. Per-node fan-out is on virtual threads so one
 * slow or unreachable broker never blocks its siblings or another cluster. A tier A
 * read that fails to reach the broker lands on {@code broker_node.last_error}; a failure
 * of any other job is logged and leaves the node's reachability as it was.
 */
// ponytail: tier B just refreshes listQueues page 1. Artemis 2.44 sortColumn /
// GREATER_THAN both 500 with an NPE (Slice 0), so a broker-sorted "hot page" is
// not fetchable — tier C is the coverage guarantee, tier B is best-effort speed.
@Component
@DependsOn("settingsService")
@RequiredArgsConstructor
@Slf4j
public class ScrapeScheduler implements DisposableBean {

    /**
     * How long a tier A pass waits for its probes before it carries on without the ones still
     * running. A node that has not answered by then is left to finish on its own and is not probed
     * again until it does.
     */
    private static final Duration PROBE_GRACE = Duration.ofSeconds(1);

    /** Why each node's job last failed, by cluster, node and job, so a node that keeps failing the same way is logged once. */
    private final Map<String, String> lastFailures = new ConcurrentHashMap<>();

    /** The nodes a tier A probe is running for on this replica. */
    private final Set<UUID> probesInFlight = ConcurrentHashMap.newKeySet();

    /** Where tier A probes run: one virtual thread each, so a stalled node holds nothing but its own. */
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    private static final int PAGE_SIZE = 200;

    private final SettingsService settings;
    private final ClusterDirectory clusters;
    private final ClusterOwnership ownership;
    private final ClusterService clusterService;
    private final BrokerGateway gateway;
    private final ScrapeCycle scrapeCycle;
    private final NodeStateRecorder persist;
    private final QueueSnapshotUpsert upsert;
    private final MetricSampleWriter metrics;
    private final StreamSignals streamSignals;
    private final BrokerNotifications notifications;
    private final ApplicationEventPublisher eventPublisher;

    private record QueuesPage(List<QueueRow> rows, Optional<String> next) {}

    // ---- tiers -------------------------------------------------------------

    /** The probes' threads are not a bean's, so nothing else ends them when the context closes. */
    @Override
    public void destroy() {
        probes.shutdownNow();
    }

    /** Tiers A and B visit the clusters this replica owns; C and discovery run once for all (ShedLock). */
    public void tierA() {
        for (RegisteredCluster cluster : clusters.owned()) {
            tierA(cluster.getId());
        }
    }

    /**
     * One tier A pass for one cluster. Each node is probed on its own and never twice at once, so a
     * node that stalls is skipped until it answers while its siblings are probed every pass. The
     * pass waits only {@link #PROBE_GRACE} for its probes; the cycle is judged by the last of its
     * probes to finish, so a slow node's reading is part of it. Taking a cluster over runs a pass
     * at once, which can overlap the scheduled one.
     */
    private void tierA(UUID clusterId) {
        long cycle = scrapeCycle.next(clusterId);
        List<ClusterNode> targets = manageableNodes(clusterId).stream()
                .filter(node -> probesInFlight.add(node.getId()))
                .toList();
        if (targets.isEmpty()) {
            completeTierA(clusterId);
            return;
        }
        AtomicInteger pending = new AtomicInteger(targets.size());
        List<Future<?>> started = targets.stream()
                .<Future<?>>map(node -> probes.submit(() -> {
                    try {
                        runIsolated(node, "HA read", n -> probe(clusterId, n, cycle));
                    } finally {
                        probesInFlight.remove(node.getId());
                        if (pending.decrementAndGet() == 0) {
                            completeTierA(clusterId);
                        }
                    }
                }))
                .toList();
        awaitProbes(started);
    }

    /** What follows once every probe of a cycle has finished: corroboration, stream signals and subscriptions. */
    private void completeTierA(UUID clusterId) {
        try {
            List<NodeEndpoint> endpoints = persist.endpoints(clusterId);
            scrapeCycle.corroborate(clusterId, endpoints);
            streamSignals.afterTierA(clusterId, endpoints);
            // Follow failover: reconcile Core notification subscriptions against
            // who is live now. Never in a transaction, on this virtual-thread pool.
            notifications.reconcile(clusterId, endpoints);
            if (!ownership.owns(clusterId)) {
                // Ownership moved while this pass ran, after the release had already dropped its state.
                notifications.forget(clusterId);
            }
        } catch (RuntimeException e) {
            log.warn("Split-brain corroboration failed for cluster {}: {}", clusterId, e.toString());
        }
        eventPublisher.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.A));
    }

    private static void awaitProbes(List<Future<?>> started) {
        long deadline = System.nanoTime() + PROBE_GRACE.toNanos();
        for (Future<?> probe : started) {
            try {
                probe.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException _) {
                // Still running: it lands its own result when it answers.
            } catch (ExecutionException e) {
                log.warn("Scrape task failed unexpectedly: {}", e.getCause() != null ? e.getCause() : e, e);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A cluster just became this replica's: scrape it now, so its state and subscriptions do not wait a tick. */
    @EventListener
    void onDutyAcquired(ClusterDutyAcquired acquired) {
        Thread.startVirtualThread(() -> {
            try {
                tierA(acquired.clusterId());
            } catch (RuntimeException e) {
                log.warn(
                        "First scrape of cluster {} after taking it over failed: {}",
                        acquired.clusterId(),
                        e.toString());
            }
        });
    }

    /**
     * A cluster stopped being this replica's: drop what it holds for it, namely Core notification
     * subscriptions (the new owner subscribes, and two would insert every event twice), the scrape
     * cycle and its corroboration ratchet, and the stream signatures. It costs the new owner one
     * extra cycle, the same as a restart.
     */
    @EventListener
    void onDutyReleased(ClusterDutyReleased released) {
        UUID clusterId = released.clusterId();
        scrapeCycle.forget(clusterId);
        streamSignals.forget(clusterId);
        lastFailures.keySet().removeIf(key -> key.startsWith(clusterId + "/"));
        Thread.startVirtualThread(() -> notifications.forget(clusterId));
    }

    public void tierB() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RegisteredCluster cluster : clusters.owned()) {
                UUID clusterId = cluster.getId();
                fanOut(pool, manageableNodes(clusterId), "hot queue read", node -> scrapeHotQueues(clusterId, node));
                eventPublisher.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.B));
            }
        }
    }

    public void tierC() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RegisteredCluster cluster : clusters.clusters()) {
                UUID clusterId = cluster.getId();
                fanOut(pool, manageableNodes(clusterId), "queue sweep", node -> sweepQueues(clusterId, node));
                eventPublisher.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.C));
            }
        }
    }

    /** One cluster per virtual thread, so a slow seed on one cluster never delays another's. */
    public void discovery() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RegisteredCluster cluster : clusters.clusters()) {
                UUID clusterId = cluster.getId();
                pool.submit(() -> {
                    try {
                        clusterService.rediscover(clusterId);
                    } catch (RuntimeException e) {
                        log.warn("Topology discovery failed for cluster {}: {}", clusterId, e.toString());
                    }
                });
            }
        }
    }

    // ---- per-node jobs ---------------------------------------------------

    /**
     * The reachability probe, and the only job that writes or clears {@code broker_node.last_error}.
     * A read that never got an answer marks the node; an answer that carries an error is a failed
     * observation, so the node keeps the state and the error it had.
     */
    private void probe(UUID clusterId, ClusterNode node, long cycle) {
        NodeDescription ha;
        try {
            ha = gateway.cluster(clusterId)
                    .nodes()
                    .describe(Scope.node(BrokerGateway.idOf(node.getId())))
                    .orThrow()
                    .getFirst();
        } catch (BrokerException failure) {
            RuntimeException e = BrokerFailures.translate(failure);
            // An answer that carries an error is a failed observation: the node keeps its state and error.
            if (failure.info().metadata().containsKey(BrokerFailures.NATIVE_ERROR)) {
                throw e;
            }
            // Rahdar held the read back itself, so the node was never asked and nothing is known of it.
            BrokerConnectionException.Kind kind = e instanceof BrokerConnectionException bce ? bce.kind() : null;
            if (kind != BrokerConnectionException.Kind.THROTTLED) {
                String message =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                persist.recordNodeError(node.getId(), message, kind);
            }
            throw e;
        }
        persist.applyTierA(node.getId(), ha, cycle);
    }

    private void scrapeHotQueues(UUID clusterId, ClusterNode node) {
        QueuesPage page = listQueues(clusterId, node, Optional.empty());
        upsert.upsertBatch(page.rows());
        metrics.appendQueueSamples(page.rows());
        streamSignals.afterQueueScrape(clusterId, page.rows());
    }

    /**
     * Reads every page of the node's queues, each written as it arrives so memory holds one page, then
     * reaps the rows the walk did not touch. A walk that fails or is interrupted part way reaps nothing:
     * the next tick starts over. Each page is one POST through the node's rate limiter.
     */
    private void sweepQueues(UUID clusterId, ClusterNode node) {
        Instant sweepStart = Instant.now();
        Optional<String> next = Optional.empty();
        do {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            QueuesPage page = listQueues(clusterId, node, next);
            upsert.upsertBatch(page.rows());
            metrics.appendQueueSamples(page.rows());
            streamSignals.afterQueueScrape(clusterId, page.rows());
            next = page.next();
        } while (next.isPresent());
        int reaped = upsert.reapStale(node.getId(), sweepStart);
        if (reaped > 0) {
            log.info("Sweep of {} complete: reaped {} stale queue rows", node.getName(), reaped);
        }
    }

    // ---- plumbing -------------------------------------------------------

    /** One page of the node's queues; each is one request through the node's rate ceiling. */
    private QueuesPage listQueues(UUID clusterId, ClusterNode node, Optional<String> from) {
        ResourceQuery query = ResourceQuery.of(ResourceClass.SUBSCRIPTION)
                .in(Scope.node(BrokerGateway.idOf(node.getId())))
                .limit(PAGE_SIZE);
        ResourcePage page;
        try {
            page = gateway.cluster(clusterId)
                    .resources()
                    .read(from.map(query::continueFrom).orElse(query))
                    .orThrow();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
        List<QueueRow> rows = page.items().stream()
                .map(r -> QueueRow.of(clusterId, node.getId(), r))
                .toList();
        return new QueuesPage(rows, page.continueToken());
    }

    private List<ClusterNode> manageableNodes(UUID clusterId) {
        return clusters.nodes(clusterId).stream()
                .filter(n -> n.getManagementUrl() != null)
                .toList();
    }

    private void fanOut(ExecutorService pool, List<ClusterNode> targets, String jobName, NodeJob job) {
        List<Future<?>> futures = targets.stream()
                .<Future<?>>map(node -> pool.submit(() -> runIsolated(node, jobName, job)))
                .toList();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.warn("Scrape task failed unexpectedly: {}", e.getCause() != null ? e.getCause() : e, e);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runIsolated(ClusterNode node, String jobName, NodeJob job) {
        String key = node.getClusterId() + "/" + node.getId() + "/" + jobName;
        try {
            // The client waits for the node's ceiling before each request it sends (ADR-0076).
            job.run(node);
            lastFailures.remove(key);
        } catch (RuntimeException e) {
            // Only the tier A probe says whether a node is reachable; what failed here is not that.
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            String why = root == e ? e.toString() : e + ", caused by " + root;
            if (!why.equals(lastFailures.put(key, why))) {
                log.warn("The {} of {} ({}) failed: {}", jobName, node.getName(), node.getManagementUrl(), why);
            }
        }
    }

    @FunctionalInterface
    private interface NodeJob {
        void run(ClusterNode node);
    }
}
