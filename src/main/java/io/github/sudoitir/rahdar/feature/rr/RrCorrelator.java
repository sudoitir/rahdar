package io.github.sudoitir.rahdar.feature.rr;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.rahdar.feature.rr.FlowStateMachine.FlowContext;
import io.github.sudoitir.rahdar.feature.rr.FlowStateMachine.Transition;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowRepository;
import io.github.sudoitir.rahdar.kernel.core.PartitionProvisioner;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives {@code rr_flow} through its six states from normalised
 * {@link Observation}s, from either channel (design.md D2). Owns the reply join
 * (D3), deadline resolution (design.md), and the current-responder tracker the
 * deadline sweep and the creation path both need to tell {@code ORPHANED} from
 * {@code TIMED_OUT}.
 */
@Service
@Slf4j
public class RrCorrelator implements RrObservationSink {

    private final RrFlowRepository flows;
    private final RrEventRepository events;
    private final RrExpectationRepository expectations;
    private final RrMetrics metrics;
    private final ReplyDestinationResolver replyDestinations;
    private final ClockOffsetService clocks;
    private final SseHub sseHub;
    private final ObjectMapper mapper;
    private final RrPayloads payloads;
    private volatile int defaultDeadlineMs;
    private volatile int payloadCaptureBytes;

    /** The error bar on an observed latency: nothing between two ticks can be resolved. */
    private volatile int sampleIntervalMs;

    /** {@code clusterId|address -> currently observed responder consumer, or null}. In-memory, address-scoped (not per-flow) — a request-reply address either has a responder or it doesn't. */
    private final Map<String, String> currentResponder = new ConcurrentHashMap<>();

    /** Dedupe hot path: {@code clusterId|address|messageId -> flowId} (design.md D5). Postgres stays authoritative — a cache miss just costs one extra query. */
    private final Cache<String, UUID> recentRequestFlow = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private final PartitionProvisioner partitions;
    private final TransactionTemplate transactions;

    public RrCorrelator(
            RrFlowRepository flows,
            RrEventRepository events,
            RrExpectationRepository expectations,
            RrMetrics metrics,
            ReplyDestinationResolver replyDestinations,
            ClockOffsetService clocks,
            SseHub sseHub,
            ObjectMapper mapper,
            RrPayloads payloads,
            RrProperties properties,
            PartitionProvisioner partitions,
            PlatformTransactionManager transactionManager) {
        this.partitions = partitions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.flows = flows;
        this.events = events;
        this.expectations = expectations;
        this.metrics = metrics;
        this.replyDestinations = replyDestinations;
        this.clocks = clocks;
        this.sseHub = sseHub;
        this.mapper = mapper;
        this.payloads = payloads;
        this.defaultDeadlineMs = properties.defaultDeadlineMs();
        this.payloadCaptureBytes = properties.payloadCaptureBytes();
        this.sampleIntervalMs = (int) properties.sampleInterval().toMillis();
    }

    /**
     * Runtime override hooks — {@code SettingsService} pushes here rather than the
     * correlator pulling, because these are read once per observed message and the
     * correlator is the hottest path in request-reply tracing.
     */
    public void setDefaultDeadlineMs(int defaultDeadlineMs) {
        this.defaultDeadlineMs = Math.max(1, defaultDeadlineMs);
    }

    public void setPayloadCaptureBytes(int payloadCaptureBytes) {
        this.payloadCaptureBytes = Math.max(1, payloadCaptureBytes);
    }

    /** Kept in step with the sampler's cadence, because it is the width of the error bar. */
    public void setSampleIntervalMs(int sampleIntervalMs) {
        this.sampleIntervalMs = Math.max(1, sampleIntervalMs);
    }

    /**
     * Each observation is its own transaction. One whose event finds no {@code rr_event} partition creates
     * it and is applied once more (ADR-0193), which is why the transaction is opened here and not by an annotation.
     */
    @Override
    public void accept(Observation observation) {
        partitions.retryingNoPartition(() -> transactions.execute(status -> {
            apply(observation);
            return null;
        }));
    }

    private void apply(Observation observation) {
        switch (observation) {
            case Observation.RequestSeen r -> onRequestSeen(r);
            case Observation.ReplySeen r -> onReplySeen(r);
            case Observation.ResponderUp r -> onResponderUp(r);
            case Observation.ResponderDown r -> onResponderDown(r);
            case Observation.TempQueueUnbound r -> onTempQueueUnbound(r);
            case Observation.MessageExpired r -> onMessageExpired(r);
        }
    }

    private void onRequestSeen(Observation.RequestSeen r) {
        String dedupeKey = r.clusterId() + "|" + r.requestDestination() + "|" + r.messageId();
        if (recentRequestFlow.getIfPresent(dedupeKey) != null) {
            return;
        }
        Optional<RrFlowEntity> existing = flows.findByClusterIdAndRequestDestinationAndRequestMessageId(
                r.clusterId(), r.requestDestination(), r.messageId());
        if (existing.isPresent()) {
            recentRequestFlow.put(dedupeKey, existing.get().getId());
            return;
        }

        RrExpectationEntity expectation = expectationFor(r.clusterId(), r.requestDestination());
        String replyKind = r.replyTo() != null ? "TEMPORARY" : "SHARED";
        // A shared-queue flow is stamped with its reply destination only when it is
        // knowable in advance — one resolved literal. Under a pattern the stamp would
        // be a guess and wrong most of the time, so the joining reply fills it in
        // instead, which also makes the flow record *which* responder answered
        // (design.md, D5). Joining never depends on it: findOpenMatches matches a
        // shared-queue flow on correlation identity alone.
        String destination = r.replyTo();
        if (destination == null && expectation != null) {
            ReplyDestinationResolver.Resolution resolved = replyDestinations.resolve(r.clusterId(), expectation);
            destination = resolved.singleLiteral() ? resolved.addresses().getFirst() : null;
        }
        Instant deadline = deadlineAt(r, expectation);

        RrFlowEntity flow = new RrFlowEntity(new RrFlowEntity.Awaiting(
                r.clusterId(),
                r.nodeId(),
                r.requestDestination(),
                destination,
                replyKind,
                r.correlationId(),
                r.messageId(),
                r.at(),
                deadline));
        flow.setResponderConsumer(currentResponder.get(r.clusterId() + "|" + r.requestDestination()));
        flow.setRequestEnqueuedAt(r.enqueuedAt());
        Long skew = forwardSkew(r.enqueuedAt(), r.at());
        flow.setRequestSkewMs(skew);
        flows.save(flow);
        if (skew != null) {
            recordEvent(flow.getId(), r.nodeId(), "CLOCK_SKEW", skewDetail("request", skew));
        }
        recentRequestFlow.put(dedupeKey, flow.getId());

        boolean capturePayload = expectation != null && expectation.isCapturePayload();
        recordEvent(
                flow.getId(),
                r.nodeId(),
                "REQUEST_SEEN",
                capturePayload
                        ? payload(
                                r.clusterId(),
                                r.requestDestination(),
                                flow.getId(),
                                "REQUEST_SEEN",
                                r.at(),
                                r.bodyPreview())
                        : null);
        sseHub.publishAboutAddress(r.clusterId(), "rr", r.requestDestination());
    }

    private void onReplySeen(Observation.ReplySeen r) {
        List<RrFlowEntity> matches =
                flows.findOpenMatches(r.clusterId(), r.replyDestination(), r.correlationId(), PageRequest.of(0, 1));
        if (matches.isEmpty()) {
            String replyKind = r.correlationId() != null ? "SHARED" : "TEMPORARY";
            RrFlowEntity orphan = RrFlowEntity.orphanedReply(
                    r.clusterId(), r.nodeId(), r.replyDestination(), replyKind, r.correlationId(), r.at());
            flows.save(orphan);
            recordEvent(orphan.getId(), r.nodeId(), "ORPHANED_REPLY", null);
            sseHub.publish(r.clusterId(), "rr");
            return;
        }

        RrFlowEntity flow = matches.getFirst();
        // A request whose own timestamp was already disbelieved must not be allowed
        // to measure anything: the state machine falls back to observation when it
        // is handed nothing to trust.
        Instant trustedRequestEnqueuedAt = flow.getRequestSkewMs() == null ? flow.getRequestEnqueuedAt() : null;
        FlowContext ctx = new FlowContext(
                RrState.valueOf(flow.getState()),
                flow.getRequestedAt(),
                flow.getRequestMessageId(),
                flow.getReplyDestination(),
                trustedRequestEnqueuedAt);
        Optional<Transition> transition = FlowStateMachine.apply(ctx, r);
        if (transition.isEmpty()) {
            return;
        }
        Transition t = transition.get();
        flow.setState(t.next().name());
        if (flow.getReplyDestination() == null) {
            flow.setReplyDestination(r.replyDestination());
        }
        flow.setRepliedAt(r.at());
        flow.setReplyMessageId(r.messageId());
        flow.setLatencyMs(t.latencyMs());
        flow.setLatencySource(t.latencySource().name());
        // An observed latency is only accurate to the tick that produced it; saying
        // so is the difference between a number and a measurement.
        flow.setLatencyBoundMs(t.latencySource() == FlowStateMachine.LatencySource.OBSERVED ? sampleIntervalMs : null);
        flow.setReplyEnqueuedAt(r.enqueuedAt());
        Long replySkew = forwardSkew(r.enqueuedAt(), r.at());
        flow.setReplySkewMs(replySkew);
        flow.setObservedAt(r.at());
        flows.save(flow);
        if (replySkew != null) {
            recordEvent(flow.getId(), r.nodeId(), "CLOCK_SKEW", skewDetail("reply", replySkew));
        }

        if (t.latencyMs() != null) {
            metrics.recordCompletion(flow.getClusterId(), flow.getRequestDestination(), t.latencyMs());
        }
        RrExpectationEntity expectation = flow.getRequestDestination() == null
                ? null
                : expectationFor(flow.getClusterId(), flow.getRequestDestination());
        boolean capturePayload = expectation != null && expectation.isCapturePayload();
        recordEvent(
                flow.getId(),
                r.nodeId(),
                t.eventKind(),
                capturePayload
                        ? payload(
                                r.clusterId(),
                                r.replyDestination(),
                                flow.getId(),
                                t.eventKind(),
                                r.at(),
                                r.bodyPreview())
                        : null);
        sseHub.publishAboutAddress(r.clusterId(), "rr", flow.getRequestDestination());
    }

    private void onResponderUp(Observation.ResponderUp r) {
        currentResponder.put(r.clusterId() + "|" + r.requestDestination(), r.consumerName());
        // Backfill: a request observed before any responder existed created its flow with
        // responderConsumer null. If a responder now attaches while that flow is still
        // awaiting reply, the eventual timeout must be TIMED_OUT, not ORPHANED — "no
        // responder was ever observed" covers the flow's whole lifetime, not just the
        // instant it was created.
        for (RrFlowEntity flow : flows.findByClusterIdAndRequestDestinationAndState(
                r.clusterId(), r.requestDestination(), RrState.AWAITING_REPLY.name())) {
            if (flow.getResponderConsumer() == null) {
                flow.setResponderConsumer(r.consumerName());
                flows.save(flow);
            }
        }
    }

    private void onResponderDown(Observation.ResponderDown r) {
        String key = r.clusterId() + "|" + r.requestDestination();
        if (r.remainingConsumers() == 0) {
            currentResponder.remove(key);
        }
        for (RrFlowEntity flow : flows.findByClusterIdAndRequestDestinationAndState(
                r.clusterId(), r.requestDestination(), RrState.AWAITING_REPLY.name())) {
            FlowContext ctx = new FlowContext(
                    RrState.AWAITING_REPLY,
                    flow.getRequestedAt(),
                    flow.getRequestMessageId(),
                    flow.getReplyDestination());
            FlowStateMachine.apply(ctx, r).ifPresent(t -> applyTransition(flow, t, r.at()));
        }
    }

    private void onTempQueueUnbound(Observation.TempQueueUnbound r) {
        for (RrFlowEntity flow : flows.findByClusterIdAndReplyDestinationAndState(
                r.clusterId(), r.queueName(), RrState.AWAITING_REPLY.name())) {
            FlowContext ctx = new FlowContext(
                    RrState.AWAITING_REPLY,
                    flow.getRequestedAt(),
                    flow.getRequestMessageId(),
                    flow.getReplyDestination());
            FlowStateMachine.apply(ctx, r).ifPresent(t -> applyTransition(flow, t, r.at()));
        }
    }

    private void onMessageExpired(Observation.MessageExpired r) {
        Optional<RrFlowEntity> flow = flows.findByClusterIdAndRequestDestinationAndRequestMessageId(
                r.clusterId(), r.address(), r.messageId());
        flow.filter(f -> RrState.AWAITING_REPLY.name().equals(f.getState())).ifPresent(f -> {
            FlowContext ctx = new FlowContext(
                    RrState.AWAITING_REPLY, f.getRequestedAt(), f.getRequestMessageId(), f.getReplyDestination());
            FlowStateMachine.apply(ctx, r).ifPresent(t -> applyTransition(f, t, r.at()));
        });
    }

    private void applyTransition(RrFlowEntity flow, Transition t, Instant at) {
        flow.setState(t.next().name());
        flow.setObservedAt(at);
        flows.save(flow);
        recordEvent(flow.getId(), flow.getNodeId(), t.eventKind(), null);
        sseHub.publishAboutAddress(flow.getClusterId(), "rr", flow.getRequestDestination());
    }

    /**
     * Forward skew only, in milliseconds, or null when there is nothing to report.
     *
     * <p>Rahdar cannot tell "produced in the future" from "produced and then sat on
     * the queue", so a timestamp <em>earlier</em> than the observation is ordinary
     * residency and proves nothing. A timestamp <em>later</em> than the moment
     * Rahdar read it cannot be explained that way: the clock that stamped it
     * disagrees with this one.
     */
    private Long forwardSkew(Instant enqueuedAt, Instant observedAt) {
        if (enqueuedAt == null || observedAt == null) {
            return null;
        }
        long ahead = Duration.between(observedAt, enqueuedAt).toMillis();
        return ahead > clocks.toleranceMs() ? ahead : null;
    }

    private Map<String, Object> skewDetail(String side, long skewMs) {
        return Map.of("clockSkew", Map.of("side", side, "aheadOfRahdarMs", skewMs));
    }

    /**
     * When this flow stops being allowed to wait, on <em>Rahdar's</em> clock.
     *
     * <p>{@code JMSExpiration} is absolute and was stamped by the producer: its own
     * clock plus the TTL it asked for. The deadline sweep compares against Rahdar's
     * clock, so before this normalisation a producer running ten minutes fast meant
     * no flow ever timed out, and ten minutes slow meant every flow timed out the
     * moment it was seen. The broker's measured offset is removed here (ADR-0053)
     * so the comparison downstream is between two readings of the same clock.
     */
    private Instant deadlineAt(Observation.RequestSeen r, RrExpectationEntity expectation) {
        if (r.expiration() > 0) {
            return clocks.brokerTime().toRahdarTime(r.nodeId(), r.expiration());
        }
        if (expectation != null && expectation.getDeadlineMs() != null) {
            return r.at().plusMillis(expectation.getDeadlineMs());
        }
        return r.at().plusMillis(defaultDeadlineMs);
    }

    /**
     * Whether {@code address} is a currently-enabled traced request address for
     * this cluster. {@code activemq.notifications} fires consumer/binding events
     * for every address on the broker, traced or not — callers use this to avoid
     * tracking responder state, or matching a delivery as a reply, for addresses
     * nobody asked Rahdar to trace.
     */
    boolean isTracedRequestDestination(UUID clusterId, String address) {
        return expectations.findByClusterIdOrderByRequestDestination(clusterId).stream()
                .anyMatch(e -> e.isEnabled() && e.getRequestDestination().equals(address));
    }

    /** Whether some flow is still awaiting a reply on this exact temp-queue destination. */
    boolean hasOpenTempQueueFlow(UUID clusterId, String replyDestination) {
        return !flows.findByClusterIdAndReplyDestinationAndState(
                        clusterId, replyDestination, RrState.AWAITING_REPLY.name())
                .isEmpty();
    }

    /**
     * Whether {@code address} is a reply address of some enabled expectation on this
     * cluster — a literal it names, or one its patterns cover.
     *
     * <p>Used to let {@code MESSAGE_DELIVERED} count as a reply observation for the
     * shared-queue pattern (design.md, D6). The sampler browses page 1 every few
     * seconds, so a reply consumed in milliseconds is never in the queue when it
     * looks; this is the second reason such flows time out. The notification carries
     * no correlation id, so the join still falls to {@code findOpenMatches} — meaning
     * this completes a flow only where sampling already supplied the correlation
     * identity, and otherwise records an orphaned reply. Coverage, not a replacement
     * for browsing.
     */
    boolean isTracedReplyDestination(UUID clusterId, String address) {
        return expectations.findByClusterIdOrderByRequestDestination(clusterId).stream()
                .anyMatch(e -> e.isEnabled() && replyDestinations.matches(e, address));
    }

    private RrExpectationEntity expectationFor(UUID clusterId, String requestDestination) {
        return expectations.findByClusterIdOrderByRequestDestination(clusterId).stream()
                .filter(e -> e.getRequestDestination().equals(requestDestination))
                .findFirst()
                .orElse(null);
    }

    /** Stamped with this instance's clock, never the broker's: the stamp picks the day's partition (ADR-0193). */
    private void recordEvent(UUID flowId, UUID nodeId, String kind, Map<String, Object> detail) {
        String json = detail == null || detail.isEmpty() ? null : mapper.writeValueAsString(detail);
        events.save(new RrEventEntity(flowId, nodeId, kind, Instant.now(), json));
    }

    /**
     * A captured body, truncated to {@code rahdar.rr.payload-capture-bytes} (design.md, bounded capture)
     * and stored governed: masked, with its originals sealed to this event (ADR-0075 D4).
     */
    private Map<String, Object> payload(
            UUID clusterId, String address, UUID flowId, String kind, Instant at, String bodyPreview) {
        if (bodyPreview == null) {
            return null;
        }
        boolean truncated = bodyPreview.length() > payloadCaptureBytes;
        String stored = truncated ? bodyPreview.substring(0, payloadCaptureBytes) : bodyPreview;
        return payloads.stored(clusterId, address, flowId, kind, at, stored, truncated);
    }
}
