package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Which queue to browse for a traced address, and how it is routed.
 *
 * <p>The sampler used to assume {@code (address, address, "ANYCAST")}. That is true
 * of the common case and wrong in two that matter: a multicast address is served
 * by queues whose names are the subscription, not the address, and an anycast
 * queue may simply be named differently from the address it is on. Both failed
 * every tick into a throttled warning the operator never saw, which is one of the
 * ways tracing could produce nothing and explain nothing.
 *
 * <p>Resolution reads {@code subscription_snapshot}, which the scrape loop already fills,
 * so this costs no broker call — the same trade {@link ReplyAddressResolver}
 * makes, and the same consequence: a queue created since the last scrape is
 * invisible until the next one.
 */
@Component
@RequiredArgsConstructor
public class QueueTargetResolver {

    /** Matches {@link ReplyAddressResolver}: the sampler asks once per address per tick. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /**
     * The queue to browse on a node.
     *
     * @param queueName the queue's own name, which is not always the address
     * @param routingType {@code ANYCAST} or {@code MULTICAST}, as the broker reports it
     */
    public record QueueTarget(String queueName, String routingType) {}

    private final QueueSnapshots snapshots;

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    /**
     * The queue serving {@code address} on {@code nodeId}, if the last scrape saw one.
     *
     * <p>Prefers a queue on that node; a queue seen elsewhere on the cluster is the
     * fallback, because on a symmetric cluster the name is the same and browsing the
     * right name on a node that has not been scraped yet still works. Empty means the
     * cluster has no such queue, which is a reason to report rather than an exception
     * to throw once a tick.
     */
    public Optional<QueueTarget> resolve(UUID clusterId, UUID nodeId, String address) {
        List<QueueSnapshot> rows = rowsFor(clusterId);
        QueueSnapshot onThisNode = null;
        QueueSnapshot anywhere = null;
        for (QueueSnapshot row : rows) {
            if (address.equals(row.address())) {
                if (nodeId != null && nodeId.equals(row.nodeId())) {
                    onThisNode = row;
                    break;
                }
                if (anywhere == null) {
                    anywhere = row;
                }
            }
        }
        QueueSnapshot chosen = onThisNode != null ? onThisNode : anywhere;
        return chosen == null
                ? Optional.empty()
                : Optional.of(new QueueTarget(chosen.queueName(), chosen.routingType()));
    }

    private List<QueueSnapshot> rowsFor(UUID clusterId) {
        Cached cached = cache.get(clusterId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.rows();
        }
        List<QueueSnapshot> rows = snapshots.forCluster(clusterId);
        cache.put(clusterId, new Cached(rows, now.plus(CACHE_TTL)));
        return rows;
    }

    private record Cached(List<QueueSnapshot> rows, Instant expiresAt) {}
}
