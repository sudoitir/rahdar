package io.github.sudoitir.rahdar.platform.clusters;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.rahdar.kernel.replica.BusResumed;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilityProbe;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A cluster's capability assessment, probed at most once per {@link #TTL} per cluster and shared by every
 * reader. A page view used to run the live Jolokia probe itself, inside a read-only transaction: a management
 * call per view (non-negotiable #1), a database connection held across network I/O, and a queue behind the
 * per-node rate limiter.
 *
 * <p>The probe runs on its own virtual thread, so it is never inside a caller's transaction, and concurrent
 * readers of one cluster join the same in-flight probe (single flight). The outcome is cached whether it is an
 * assessment or the failure to make one, so an unreachable broker is not asked again on every view; a failure
 * stays what it was, a {@link BrokerConnectionException}, and never blocks longer than one probe.
 *
 * <p>{@link #invalidate} drops a cluster's entry here and on every replica when its nodes or credentials
 * change (ADR-0152); the bus coming back drops everything.
 */
@Component
public class CapabilityAssessments {

    static final Duration TTL = Duration.ofSeconds(15);
    private static final String SIGNAL = "capability-assessment";

    /** An assessment, or the reason there is none. */
    public record Outcome(BrokerCapabilities capabilities, BrokerConnectionException failure) {

        public BrokerCapabilities orThrow() {
            if (failure != null) {
                throw failure;
            }
            return capabilities;
        }
    }

    private final BrokerNodeRepository nodes;
    private final BrokerGateway gateway;
    private final BrokerCapabilityProbe probe;
    private final CapabilityLedger ledger;
    private final BrokerNotifications coreSubscriptions;
    private final RahdarBus bus;

    private final AsyncCache<UUID, Outcome> cache;

    CapabilityAssessments(
            BrokerNodeRepository nodes,
            BrokerGateway gateway,
            BrokerCapabilityProbe probe,
            CapabilityLedger ledger,
            BrokerNotifications coreSubscriptions,
            RahdarBus bus) {
        this.nodes = nodes;
        this.gateway = gateway;
        this.probe = probe;
        this.ledger = ledger;
        this.coreSubscriptions = coreSubscriptions;
        this.bus = bus;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .buildAsync();
    }

    /** The cluster's assessment, probing only when the cached one is older than {@link #TTL}. Never throws for a probe failure. */
    public Outcome outcome(UUID clusterId) {
        CompletableFuture<Outcome> future =
                cache.get(clusterId, (id, executor) -> CompletableFuture.supplyAsync(() -> assess(id), executor));
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Drops the cluster's entry on every replica; call when its nodes or credentials change. */
    public void invalidate(UUID clusterId) {
        cache.synchronous().invalidate(clusterId);
        bus.publish(new ReplicaSignal(SIGNAL, clusterId.toString()));
    }

    @EventListener(condition = "#signal.kind() == 'capability-assessment'")
    void onSignal(ReplicaSignal signal) {
        cache.synchronous().invalidate(UUID.fromString(signal.key()));
    }

    /** The bus was down: a cluster may have changed in the gap. */
    @EventListener
    void onBusResumed(BusResumed resumed) {
        cache.synchronous().invalidateAll();
    }

    private Outcome assess(UUID clusterId) {
        try {
            BrokerNodeEntity manageable = manageableNode(clusterId);
            BrokerTarget target = gateway.node(clusterId, manageable.getId());
            return new Outcome(
                    probe.probe(target, coreSubscriptions.verdictFor(clusterId), ledger.managementWrite(clusterId)),
                    null);
        } catch (BrokerConnectionException e) {
            return new Outcome(null, e);
        }
    }

    /**
     * The node to assess capabilities against: a live one for preference, any
     * manageable one otherwise. A passive backup answers management reads but
     * registers no acceptor and no address MBeans, so probing one reports "CORE
     * acceptor not found" and "activemq.notifications address not found" about a
     * broker where both are present. Ordering by name alone made that the normal
     * outcome for any cluster whose backup sorts first.
     */
    private BrokerNodeEntity manageableNode(UUID clusterId) {
        return chooseManageable(nodes.findByClusterIdOrderByNameAsc(clusterId))
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));
    }

    static Optional<BrokerNodeEntity> chooseManageable(List<BrokerNodeEntity> nodes) {
        List<BrokerNodeEntity> manageable =
                nodes.stream().filter(n -> n.getManagementUrl() != null).toList();
        return manageable.stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()) && n.getLastError() == null)
                .findFirst()
                .or(() -> manageable.stream().findFirst());
    }
}
