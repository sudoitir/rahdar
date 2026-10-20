package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.feature.rr.QueueTargetResolver.QueueTarget;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationRepository;
import io.github.sudoitir.rahdar.feature.sql.CaptureCoverage;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.broker.CoreDestinationName;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Polls page 1 of every enabled expectation's request (and reply, when
 * configured) address over the pooled Core connection, for message
 * correlation identity notifications cannot carry (design.md D2). Broker
 * friendly by construction: bounded page size, no deep queue walk — a healthy
 * request-reply address is near-empty, so page 1 already is the backlog worth
 * seeing.
 *
 * <p>A separate trigger from {@link io.github.sudoitir.rahdar.platform.scrape.ScrapeScheduler}'s
 * Jolokia tiers on purpose — this is a Core-protocol poll and must not consume
 * the Jolokia {@code NodeCallLimiter} budget.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RrSampler {

    /** The reason tracing produces nothing on a cluster registered without a Core URL. */
    public static final String NO_CORE_ENDPOINT =
            "no node on this cluster has a reachable Core endpoint; tracing browses over the Core client";

    private static final int SAMPLE_PAGE_SIZE = 20;
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);

    private final RrExpectationRepository expectations;
    private final ClusterDirectory nodes;
    private final ClusterOwnership ownership;
    private final BrokerMessages messages;
    private final ObjectProvider<RrObservationSink> sink;
    private final ReplyAddressResolver replyAddresses;
    private final ClockOffsetService clocks;
    private final QueueTargetResolver queueTargets;
    private final RrSamplerHealth health;
    private final ObjectProvider<CaptureCoverage> captureCoverage;

    /** Per expectation-and-node, when its last failure was logged. */
    private final Map<String, Instant> lastReported = new ConcurrentHashMap<>();

    /** Whether capture already sees this address on this node; never while the SQL feature is disabled. */
    private boolean captured(UUID clusterId, UUID nodeId, String address) {
        CaptureCoverage coverage = captureCoverage.getIfAvailable();
        return coverage != null && coverage.isCaptured(clusterId, nodeId, address);
    }

    /** Per expectation, when it was last sampled — the throttle {@code samplePerMin} asks for. */
    private final Map<UUID, Instant> lastSampledAt = new ConcurrentHashMap<>();

    /**
     * Scheduled by {@code JobRunner} on {@code rr.sample-interval}. It used to
     * hardcode 5s and ignore the configured value entirely. Samples only the clusters this replica owns.
     */
    public void tick() {
        RrObservationSink target = sink.getIfAvailable();
        if (target == null) {
            return;
        }
        for (RrExpectationEntity expectation : expectations.findByEnabledTrue()) {
            if (!ownership.owns(expectation.getClusterId()) || !due(expectation)) {
                continue;
            }
            try {
                sampleExpectation(expectation, target);
            } catch (RuntimeException e) {
                log.warn("Request-reply sampling failed for '{}': {}", expectation.getRequestAddress(), e.toString());
            }
        }
    }

    /**
     * Whether this expectation's own rate allows another sample now.
     *
     * <p>{@code samplePerMin} was stored, shown in the table, and never read: the UI
     * was offering a control that did nothing. The global {@code rr.sample-interval}
     * remains the floor — a per-expectation rate faster than the tick cannot be
     * delivered, and the diagnostics say so rather than pretending.
     */
    private boolean due(RrExpectationEntity expectation) {
        int perMin = Math.max(1, expectation.getSamplePerMin());
        Duration minimumGap = Duration.ofSeconds(60).dividedBy(perMin);
        Instant last = lastSampledAt.get(expectation.getId());
        Instant now = Instant.now();
        if (last != null && last.plus(minimumGap).isAfter(now)) {
            return false;
        }
        lastSampledAt.put(expectation.getId(), now);
        return true;
    }

    private void sampleExpectation(RrExpectationEntity expectation, RrObservationSink target) {
        UUID clusterId = expectation.getClusterId();
        List<String> replyTargets =
                replyAddresses.resolve(clusterId, expectation).addresses();
        RrSamplerHealth.Tick tick = health.begin(expectation.getId(), clusterId, expectation.getRequestAddress());

        List<ClusterNode> serving = servingNodes(clusterId);
        if (serving.isEmpty()) {
            // Previously an empty loop: nothing sampled, nothing logged, nothing to
            // find. Tracing needs the Core client, and saying which node could carry
            // it is the whole diagnosis.
            tick.skipped("(cluster)", NO_CORE_ENDPOINT);
            tick.publish();
            reportOnce(
                    expectation.getId() + "|no-core",
                    () -> log.warn(
                            "Request-reply tracing for '{}' sampled nothing: {}",
                            expectation.getRequestAddress(),
                            NO_CORE_ENDPOINT));
            return;
        }

        // Every serving node, not just the first: in a three-primary cluster the
        // request and reply queues on the other two were never read, so the
        // correlation identity only browsing can supply was missing for two thirds
        // of the traffic (design.md, D7). Duplicates across nodes are already
        // handled by recentRequestFlow and uq_rr_flow_request.
        for (ClusterNode node : serving) {
            // One node failing must not cost the others their tick.
            try {
                sampleNode(expectation, node, replyTargets, target, tick);
            } catch (RuntimeException e) {
                tick.failed(node.getName(), e.toString());
                reportFailure(expectation, node, e);
            }
        }
        tick.publish();
    }

    private void sampleNode(
            RrExpectationEntity expectation,
            ClusterNode node,
            List<String> replyTargets,
            RrObservationSink target,
            RrSamplerHealth.Tick tick) {
        UUID clusterId = expectation.getClusterId();
        int browsed = 0;
        int emitted = 0;

        // A captured address is already delivering every message to the correlator
        // through CaptureBus, so browsing it here would be broker load for facts
        // Rahdar already has (ADR-0062). Sampling continues for everything else, and
        // for this address on any node capture has not reached.
        if (captured(clusterId, node.getId(), expectation.getRequestAddress())) {
            tick.skipped(node.getName(), "captured on this node; correlation comes from the capture stream");
        } else {
            for (BrowsedMessage m : browse(clusterId, node, expectation.getRequestAddress(), tick)) {
                browsed += 1;
                emitted += 1;
                target.accept(new Observation.RequestSeen(
                        clusterId,
                        node.getId(),
                        Instant.now(),
                        expectation.getRequestAddress(),
                        String.valueOf(m.messageId()),
                        correlationOf(expectation, m),
                        m.replyTo() != null ? CoreDestinationName.extract(m.replyTo()) : null,
                        m.expiration(),
                        m.bodyPreview(),
                        Map.of(),
                        enqueuedAt(node, m)));
            }
        }

        for (String replyAddress : replyTargets) {
            if (captured(clusterId, node.getId(), replyAddress)) {
                tick.skipped(node.getName(), "captured on this node; correlation comes from the capture stream");
                continue;
            }
            for (BrowsedMessage m : browse(clusterId, node, replyAddress, tick)) {
                browsed += 1;
                emitted += 1;
                target.accept(new Observation.ReplySeen(
                        clusterId,
                        node.getId(),
                        Instant.now(),
                        replyAddress,
                        String.valueOf(m.messageId()),
                        correlationOf(expectation, m),
                        m.bodyPreview(),
                        Map.of(),
                        enqueuedAt(node, m)));
            }
        }
        tick.sampled(browsed, emitted);
    }

    /**
     * A sampling failure used to be swallowed at {@code debug}, so a
     * correctly-configured-looking expectation produced nothing and said nothing.
     * It is now a {@code warn} on first occurrence and at most once a minute after,
     * naming the expectation and the node — loud enough to find, quiet enough that a
     * node down for an hour does not fill the log at the sampling cadence.
     */
    private void reportFailure(RrExpectationEntity expectation, ClusterNode node, RuntimeException e) {
        String key = expectation.getId() + "|" + node.getId();
        Instant now = Instant.now();
        Instant last = lastReported.get(key);
        if (last != null && last.isAfter(now.minus(FAILURE_LOG_INTERVAL))) {
            return;
        }
        lastReported.put(key, now);
        log.warn(
                "Request-reply sampling failed for '{}' on node {}: {}",
                expectation.getRequestAddress(),
                node.getName(),
                e.toString());
    }

    /**
     * When the message says it was produced, on Rahdar's clock.
     *
     * <p>The browse is the only channel that can supply this, and carrying it is
     * what turns latency from "the gap between two sample ticks" — which is zero for
     * two messages seen on the same tick — into the real figure. The broker's
     * measured offset is removed here so the value can be compared with anything
     * else Rahdar holds (ADR-0053). A message with no timestamp yields null, which
     * means unknown rather than the epoch.
     */
    private Instant enqueuedAt(ClusterNode node, BrowsedMessage m) {
        return m.timestamp() > 0 ? clocks.brokerTime().toRahdarTime(node.getId(), m.timestamp()) : null;
    }

    private static String correlationOf(RrExpectationEntity expectation, BrowsedMessage m) {
        if (expectation.getCorrelationProperty() != null) {
            String v = m.stringProperties().get(expectation.getCorrelationProperty());
            if (v != null) {
                return v;
            }
        }
        return m.correlationId();
    }

    /**
     * Browse one address on one node, using the queue the broker actually has.
     *
     * <p>The queue name and routing type used to be assumed to equal the address and
     * {@code ANYCAST}. A multicast address, or a queue named differently from its
     * address, failed on every tick into a throttled warning. An address the last
     * scrape never saw is now a stated reason instead.
     */
    private List<BrowsedMessage> browse(UUID clusterId, ClusterNode node, String address, RrSamplerHealth.Tick tick) {
        QueueTarget queue =
                queueTargets.resolve(clusterId, node.getId(), address).orElse(null);
        if (queue == null) {
            tick.skipped(node.getName(), "no queue for address '" + address + "' in the last scrape");
            return List.of();
        }
        return messages.browse(clusterId, node.getId(), queue.queueName(), 1, SAMPLE_PAGE_SIZE, null)
                .messages();
    }

    /** Log once per interval for a key, sharing the failure throttle's budget. */
    private void reportOnce(String key, Runnable log) {
        Instant now = Instant.now();
        Instant last = lastReported.get(key);
        if (last != null && last.isAfter(now.minus(FAILURE_LOG_INTERVAL))) {
            return;
        }
        lastReported.put(key, now);
        log.run();
    }

    private List<ClusterNode> servingNodes(UUID clusterId) {
        return nodes.nodes(clusterId).stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()) && n.getLastError() == null)
                .filter(n -> n.getCoreUrl() != null)
                .toList();
    }
}
