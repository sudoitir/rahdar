package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowRepository;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.ClockDiagnosticsView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.CreateExpectationRequest;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.ExpectationDiagnosticsView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.ExpectationView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.FlowView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.RrDiagnosticsView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.RrEventView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.TracingReasonView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.UpdateExpectationRequest;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.broker.SubscriptionVerdict;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.governance.ClearViewAudit;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernancePermissions;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Request-reply expectations (which addresses to trace, and how) and the read
 * side of reconstructed flows (request-reply-tracing spec). Every expectation
 * write is audited in the same transaction as the change (ADR-0002
 * non-negotiable #3).
 */
@Service
@RequiredArgsConstructor
public class RequestReplyService {

    private static final String AUDIT_TARGET = "RR_EXPECTATION";
    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {};

    private final RrExpectationRepository expectations;
    private final RrFlowRepository flows;
    private final RrEventRepository events;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ObjectMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final ReplyDestinationResolver replyDestinations;
    private final ClusterDirectory nodes;
    private final RrSamplerHealth samplerHealth;
    private final BrokerNotifications subscriptions;
    private final ClockOffsetService clocks;
    private final SettingsService settings;
    private final PermissionResolver permissions;
    private final RrPayloads payloads;
    private final ClearViewAudit clearViews;

    // ---- expectations -------------------------------------------------

    @Transactional(readOnly = true)
    public List<ExpectationView> list(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        return readableExpectations(clusterId).stream().map(this::toView).toList();
    }

    /** The cluster's expectations whose request and reply addresses the caller may all read. */
    private List<RrExpectationEntity> readableExpectations(UUID clusterId) {
        ResourceFilter addresses = permissions.filter(clusterId, ResourceType.DESTINATION);
        return expectations.findByClusterIdOrderByRequestDestination(clusterId).stream()
                .filter(e -> readable(addresses, e.getRequestDestination(), e.getReplyDestinations()))
                .toList();
    }

    private static boolean readable(ResourceFilter addresses, String request, List<String> replies) {
        return addresses.readable(request) && replies.stream().allMatch(addresses::readable);
    }

    /**
     * Tracing an address needs {@code rr:write} on the cluster and read access to the request address and
     * every reply address it names: a trace reports what crosses them.
     */
    private void requireMayTrace(UUID clusterId, String requestDestination, List<String> replyDestinations) {
        clusterAccess.requireCluster(clusterId, RrPermissions.RR_WRITE);
        clusterAccess.requireAll(
                clusterId,
                Stream.concat(Stream.of(requestDestination), replyDestinations.stream())
                        .map(a -> new Requirement(ResourceRef.destination(a), Permissions.DESTINATION_READ))
                        .toList());
    }

    @Transactional
    public ExpectationView create(UUID clusterId, CreateExpectationRequest request) {
        requireMayTrace(
                clusterId, request.requestDestination(), normaliseReplyDestinations(request.replyDestinations()));
        // Checked before the audit row is opened: letting the unique constraint fire
        // instead would mark the transaction rollback-only, discarding the audit row
        // with it, and surface as an unmapped 500 the operator cannot act on.
        if (expectations.existsByClusterIdAndRequestDestination(clusterId, request.requestDestination())) {
            throw new ConflictException(
                    "duplicate-rr-expectation",
                    "'" + request.requestDestination()
                            + "' is already traced on this cluster. Edit the existing expectation instead.");
        }
        AuditEvent audited = audit.begin(
                actorResolver.resolve(),
                "CREATE_RR_EXPECTATION",
                AUDIT_TARGET,
                request.requestDestination(),
                clusterId,
                null,
                Map.of("requestDestination", request.requestDestination()),
                false);
        RrExpectationEntity entity = expectations.save(new RrExpectationEntity(
                clusterId,
                request.requestDestination(),
                normaliseReplyDestinations(request.replyDestinations()),
                blankToNull(request.correlationProperty()),
                request.deadlineMs(),
                request.samplePerMin() > 0 ? request.samplePerMin() : 10,
                request.capturePayload()));
        audit.succeed(audited, 1);
        return toView(entity);
    }

    @Transactional
    public ExpectationView update(UUID clusterId, UUID expectationId, UpdateExpectationRequest request) {
        RrExpectationEntity entity = writable(clusterId, expectationId);
        requireMayTrace(
                clusterId, entity.getRequestDestination(), normaliseReplyDestinations(request.replyDestinations()));

        AuditEvent audited = audit.begin(
                actorResolver.resolve(),
                "UPDATE_RR_EXPECTATION",
                AUDIT_TARGET,
                entity.getRequestDestination(),
                clusterId,
                null,
                Map.of("enabled", request.enabled()),
                false);

        entity.setReplyDestinations(normaliseReplyDestinations(request.replyDestinations()));
        entity.setCorrelationProperty(blankToNull(request.correlationProperty()));
        entity.setDeadlineMs(request.deadlineMs());
        entity.setSamplePerMin(request.samplePerMin() > 0 ? request.samplePerMin() : entity.getSamplePerMin());
        entity.setCapturePayload(request.capturePayload());
        entity.setEnabled(request.enabled());

        audit.succeed(audited, 1);
        return toView(entity);
    }

    @Transactional
    public void delete(UUID clusterId, UUID expectationId) {
        RrExpectationEntity entity = writable(clusterId, expectationId);
        requireMayTrace(clusterId, entity.getRequestDestination(), entity.getReplyDestinations());

        AuditEvent audited = audit.begin(
                actorResolver.resolve(),
                "DELETE_RR_EXPECTATION",
                AUDIT_TARGET,
                entity.getRequestDestination(),
                clusterId,
                null,
                Map.of(),
                false);
        expectations.delete(entity);
        audit.succeed(audited, 1);
    }

    /** The expectation, when the caller may read the addresses it traces: otherwise it is not there for them. */
    private RrExpectationEntity writable(UUID clusterId, UUID expectationId) {
        clusterAccess.requireCluster(clusterId, RrPermissions.RR_WRITE);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceType.DESTINATION);
        return expectations
                .findById(expectationId)
                .filter(e -> e.getClusterId().equals(clusterId))
                .filter(e -> readable(addresses, e.getRequestDestination(), e.getReplyDestinations()))
                .orElseThrow(() -> new NotFoundException("Request-reply expectation", expectationId));
    }

    /**
     * Blanks dropped, duplicates collapsed, declaration order preserved. Null and
     * empty collapse to the same empty list on purpose: both spell the temporary-reply-queue
     * pattern, and keeping two spellings of one state would force every reader to
     * handle both (design.md, D3).
     */
    static List<String> normaliseReplyDestinations(List<String> declared) {
        if (declared == null) {
            return List.of();
        }
        return declared.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private ExpectationView toView(RrExpectationEntity e) {
        ReplyDestinationResolver.Resolution resolved = replyDestinations.resolve(e.getClusterId(), e);
        return new ExpectationView(
                e.getId(),
                e.getRequestDestination(),
                e.getReplyDestinations(),
                resolved.addresses(),
                resolved.capped(),
                e.getCorrelationProperty(),
                e.getDeadlineMs(),
                e.getSamplePerMin(),
                e.isCapturePayload(),
                e.isEnabled());
    }

    // ---- flows (read side) ---------------------------------------------

    @Transactional(readOnly = true)
    public PagedView<FlowView> flowPage(UUID clusterId, FlowQuery query) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceType.DESTINATION);
        // Filtered in the query, before paging, so a total never counts a flow on an address the caller cannot read.
        List<String> readable = addresses.everything()
                ? List.of("")
                : flows.findDistinctRequestDestinationByClusterId(clusterId).stream()
                        .filter(addresses::readable)
                        .toList();
        if (readable.isEmpty()) {
            return PagedView.of(
                    Page.<RrFlowEntity>empty(PageRequest.of(query.page() - 1, query.size())),
                    f -> toFlowView(f, false));
        }
        Page<RrFlowEntity> result = flows.findPage(
                clusterId,
                addresses.everything(),
                readable,
                blankToNull(query.state()),
                blankToNull(query.destination()),
                blankToNull(query.correlationId()),
                query.from() != null ? query.from() : Instant.EPOCH,
                query.to() != null ? query.to() : Instant.parse("9999-12-31T23:59:59Z"),
                PageRequest.of(query.page() - 1, query.size()));
        return PagedView.of(result, f -> toFlowView(f, false));
    }

    @Transactional(readOnly = true)
    public FlowView flow(UUID clusterId, UUID flowId) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceType.DESTINATION);
        RrFlowEntity entity = flows.findById(flowId)
                .filter(f -> f.getClusterId().equals(clusterId))
                .filter(f -> addresses.readable(f.getRequestDestination()))
                .orElseThrow(() -> new NotFoundException("Request-reply flow", flowId));
        return toFlowView(entity, true);
    }

    /**
     * Why tracing is, or is not, producing flows on this cluster.
     *
     * <p>The Flows tab used to render a bare {@code 0 flows}, which reads identically
     * whether nothing was sent, nothing could be browsed, or the requests were
     * consumed faster than the sampler ticks. This assembles what the sampler
     * actually did, plus the reasons ranked most-likely-first, each with its remedy.
     */
    @Transactional(readOnly = true)
    public RrDiagnosticsView diagnostics(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);

        List<ClusterNode> clusterNodes = nodes.nodes(clusterId);
        int withCore =
                (int) clusterNodes.stream().filter(n -> n.getDataUrl() != null).count();
        long sampleIntervalMs = settings.duration(RrSettings.SAMPLE_INTERVAL).toMillis();
        List<RrExpectationEntity> declared = readableExpectations(clusterId);

        List<ExpectationDiagnosticsView> perExpectation = declared.stream()
                .map(e -> expectationDiagnostics(e, clusterNodes.size(), sampleIntervalMs))
                .toList();

        return new RrDiagnosticsView(
                Instant.now(),
                sampleIntervalMs,
                withCore,
                clusterNodes.size(),
                notificationsState(clusterId),
                clockDiagnostics(clusterId),
                perExpectation,
                reasons(declared, perExpectation, withCore, sampleIntervalMs, clusterId));
    }

    private ExpectationDiagnosticsView expectationDiagnostics(
            RrExpectationEntity e, int nodesTotal, long sampleIntervalMs) {
        RrSamplerHealth.ExpectationHealth health =
                samplerHealth.forExpectation(e.getId()).orElse(null);
        // The global interval is the floor: an expectation asking for more samples per
        // minute than the tick can deliver is not getting them, and should be told.
        boolean rateExceedsInterval = 60_000L / Math.max(1, e.getSamplePerMin()) < sampleIntervalMs;
        return new ExpectationDiagnosticsView(
                e.getId(),
                e.getRequestDestination(),
                e.isEnabled(),
                health == null ? null : health.lastAttemptAt(),
                health == null ? null : health.lastSuccessAt(),
                health == null ? 0 : health.nodesSampled(),
                nodesTotal,
                health == null
                        ? List.of()
                        : health.skipped().stream()
                                .map(o -> o.nodeName() + ": " + o.reason())
                                .toList(),
                health == null ? 0 : health.messagesBrowsed(),
                health == null ? 0 : health.observations(),
                health == null ? null : health.lastError(),
                health == null ? null : health.lastErrorAt(),
                e.getSamplePerMin(),
                rateExceedsInterval);
    }

    private String notificationsState(UUID clusterId) {
        return switch (subscriptions.verdictFor(clusterId)) {
            case SubscriptionVerdict.Connected _ -> "CONNECTED";
            case SubscriptionVerdict.Failed _ -> "FAILED";
            case SubscriptionVerdict.NotAttempted _ -> "NOT_ATTEMPTED";
        };
    }

    private ClockDiagnosticsView clockDiagnostics(UUID clusterId) {
        ClockOffsetService.Assessment assessment = clocks.assessmentFor(clusterId);
        return new ClockDiagnosticsView(
                assessment.verdict().name(),
                assessment.worst().map(w -> w.offset().offsetMs()).orElse(null),
                assessment.worst().map(w -> w.offset().uncertaintyMs()).orElse(null),
                assessment.skewed().stream()
                        .map(ClockOffsetService.NodeSkew::nodeName)
                        .toList(),
                assessment.at(),
                clocks.toleranceMs());
    }

    /**
     * The ranked diagnosis. Order is what makes it useful: the first reason that
     * applies is the one to act on, and each carries the remedy rather than leaving
     * the operator to infer it.
     */
    private List<TracingReasonView> reasons(
            List<RrExpectationEntity> declared,
            List<ExpectationDiagnosticsView> health,
            int nodesWithCore,
            long sampleIntervalMs,
            UUID clusterId) {
        List<TracingReasonView> out = new ArrayList<>();

        if (declared.isEmpty()) {
            out.add(new TracingReasonView(
                    "NOTHING_DECLARED",
                    "No request destination is declared, so nothing on this cluster is being traced.",
                    "Add a request destination on the Expectations tab."));
            return out;
        }
        if (declared.stream().noneMatch(RrExpectationEntity::isEnabled)) {
            out.add(new TracingReasonView(
                    "ALL_DISABLED", "Every declared destination is disabled.", "Enable one on the Expectations tab."));
            return out;
        }
        if (nodesWithCore == 0) {
            out.add(new TracingReasonView(
                    "NO_CORE_ENDPOINT",
                    RrSampler.NO_CORE_ENDPOINT,
                    "Set a reachable Core URL on at least one node in this cluster's topology."));
        }
        health.stream()
                .filter(h -> h.enabled() && !h.skipped().isEmpty())
                .findFirst()
                .ifPresent(h -> out.add(new TracingReasonView(
                        "NODES_SKIPPED",
                        "Some nodes were not sampled for '" + h.requestDestination() + "': "
                                + String.join("; ", h.skipped()),
                        "A destination with no subscription in the last scrape is not browsable yet; "
                                + "check the destination name and how it delivers.")));
        if (health.stream().anyMatch(ExpectationDiagnosticsView::rateExceedsInterval)) {
            out.add(new TracingReasonView(
                    "RATE_ABOVE_FLOOR",
                    "A samples-per-minute setting asks for more often than rr.sample-interval (" + sampleIntervalMs
                            + "ms) can deliver.",
                    "Lower the sampler interval in Settings, or accept the global cadence."));
        }
        if ("FAILED".equals(notificationsState(clusterId))) {
            out.add(new TracingReasonView(
                    "NOTIFICATIONS_UNAVAILABLE",
                    "The broker notification subscription is not connected, so a reply drained between "
                            + "sample ticks cannot be joined to its request.",
                    "Check the Core credentials and that activemq.notifications is readable; "
                            + "the capability panel on this cluster shows the broker.xml to fix it."));
        }
        // Always last, and always present: it is the disclosed ceiling of a
        // sampling-based design (ADR-0030), not a fault.
        out.add(new TracingReasonView(
                "CONSUMED_FASTER_THAN_SAMPLED",
                "Tracing browses every " + sampleIntervalMs
                        + "ms, so a request consumed faster than that is never seen. On a healthy "
                        + "request-reply destination that is most of the traffic.",
                "Raise samples per minute and lower rr.sample-interval, or read the numbers as a "
                        + "sample rather than a census — the Latency tab states the coverage."));
        return List.copyOf(out);
    }

    private FlowView toFlowView(RrFlowEntity f, boolean withEvents) {
        return new FlowView(
                f.getId(),
                f.getClusterId(),
                f.getNodeId(),
                f.getRequestDestination(),
                f.getReplyDestination(),
                f.getReplyKind(),
                f.getState(),
                f.getCorrelationId(),
                f.getRequestedAt(),
                f.getDeadlineAt(),
                f.getRepliedAt(),
                f.getLatencyMs(),
                f.getLatencySource(),
                f.getLatencyBoundMs(),
                f.getRequestEnqueuedAt(),
                f.getReplyEnqueuedAt(),
                f.getRequestSkewMs(),
                f.getReplySkewMs(),
                withEvents ? eventViews(f) : null);
    }

    /**
     * A flow's timeline. A captured payload needs message read permission on the cluster, and is governed for the
     * caller: masked, or unsealed and marked as sensitive with clear access (request-reply-tracing spec).
     */
    private List<RrEventView> eventViews(RrFlowEntity flow) {
        UUID clusterId = flow.getClusterId();
        boolean payloadsReadable = permissions.can(clusterId, MessagePermissions.MESSAGE_READ);
        boolean clearAccess = payloadsReadable && permissions.can(clusterId, GovernancePermissions.MESSAGE_CLEAR);
        List<GovernedMessage> served = new ArrayList<>();
        List<RrEventView> views = events.findByFlowIdOrderByTsAsc(flow.getId()).stream()
                .map(e -> toEventView(flow, e, payloadsReadable, clearAccess, served))
                .toList();
        clearViews.recordClear(
                new GovernContext(clusterId, flow.getRequestDestination(), clearAccess),
                "RR_FLOW",
                flow.getId().toString(),
                served);
        return views;
    }

    private RrEventView toEventView(
            RrFlowEntity flow,
            RrEventEntity e,
            boolean payloadsReadable,
            boolean clearAccess,
            List<GovernedMessage> served) {
        Map<String, Object> detail = parseDetail(e.getDetail());
        if (detail != null && detail.containsKey(RrPayloads.BODY_PREVIEW)) {
            if (!payloadsReadable) {
                detail = Map.of(
                        "payloadOmitted",
                        "The captured payload is omitted: reading it needs the message:read permission on this"
                                + " cluster.");
            } else {
                String address =
                        "REQUEST_SEEN".equals(e.getKind()) ? flow.getRequestDestination() : flow.getReplyDestination();
                RrPayloads.Viewed viewed = payloads.view(
                        flow.getClusterId(), address, flow.getId(), e.getKind(), e.getTs(), detail, clearAccess);
                served.add(viewed.governed());
                detail = viewed.detail();
            }
        }
        return new RrEventView(e.getSeq(), e.getTs(), e.getKind(), e.getNodeId(), detail);
    }

    private Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, DETAIL_TYPE);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
