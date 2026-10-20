package io.github.sudoitir.rahdar.feature.setupreview;

import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.FindingKey;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingAcceptanceEntity;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingAcceptanceRepository;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingEntity;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingRepository;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupReviewEntity;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupReviewRepository;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.AcceptRiskRequest;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.AcceptanceView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.EvidenceView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.NotAssessedView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.ReviewedNodeView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.SetupFindingView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.SetupReviewView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.SeverityCountsView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs and reads setup reviews, and records risk acceptances (ADR-0106).
 *
 * <p>A review reads every manageable node — one batched POST each, outside any transaction — then
 * evaluates {@link SetupRules} and hands the result to {@link SetupReviewStore}. It runs under the
 * cluster's {@code SETUP_REVIEW} lock, so two Rahdar instances never review one cluster at once,
 * and never writes to a broker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SetupReviewService {

    public static final String TOPIC = "setup-review";
    private static final String REASON = "reason";

    private final ClusterDirectory clusters;
    private final BrokerConnections connections;
    private final SetupReader reader;
    private final SetupReviewStore store;
    private final SetupReviewRepository reviews;
    private final SetupFindingRepository findings;
    private final SetupFindingAcceptanceRepository acceptances;
    private final ClusterLock lock;
    private final SettingsService settings;
    private final SseHub hub;
    private final ClusterAccessGuard clusterAccess;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ObjectMapper mapper;
    private final ArtemisNodes artemisNodes;

    /** The latest review of a cluster, never running one. */
    @Transactional(readOnly = true)
    public SetupReviewView view(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return render(clusterId, null);
    }

    /**
     * Reviews a cluster now. Sooner than the minimum spacing after the last review, or while another
     * review of it is running, the current review is returned with the reason instead.
     */
    public SetupReviewView run(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        clusters.cluster(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        Duration spacing = settings.duration(SetupReviewSettings.MIN_INTERVAL);
        SetupReviewEntity last = reviews.findById(clusterId).orElse(null);
        if (last != null) {
            Duration since = Duration.between(last.getReviewedAt(), Instant.now());
            if (since.compareTo(spacing) < 0) {
                long wait = Math.max(1, spacing.minus(since).toSeconds());
                return render(
                        clusterId,
                        "Reviewed " + Math.max(0, since.toSeconds()) + "s ago; the next review can run in " + wait
                                + "s. Showing the last review.");
            }
        }
        AtomicBoolean ran = new AtomicBoolean();
        boolean held = lock.runIfHeld(clusterId, ClusterLock.Scope.SETUP_REVIEW, () -> {
            review(clusterId);
            ran.set(true);
        });
        if (!held || !ran.get()) {
            return render(
                    clusterId, "A review of this cluster is already running. Its result will appear when it finishes.");
        }
        return render(clusterId, null);
    }

    /** The scheduled pass: every cluster this replica owns, under its lock, never throwing. */
    public void reviewAll() {
        for (RegisteredCluster cluster : clusters.owned()) {
            try {
                lock.runIfHeld(cluster.getId(), ClusterLock.Scope.SETUP_REVIEW, () -> review(cluster.getId()));
            } catch (RuntimeException e) {
                log.warn("Setup review of cluster {} failed: {}", cluster.getId(), e.toString());
            }
        }
    }

    void review(UUID clusterId) {
        long started = System.nanoTime();
        List<NodeRead> reads = new ArrayList<>();
        for (ClusterNode node : clusters.nodes(clusterId)) {
            boolean live = Boolean.TRUE.equals(node.getActive());
            if (node.getManagementUrl() == null) {
                reads.add(NodeRead.unreadable(
                        node.getId(),
                        node.getName(),
                        node.getNativeNodeId(),
                        live,
                        false,
                        "Rahdar has no management URL for this node."));
                continue;
            }
            reads.add(reader.read(artemisNodes.on(clusterId, node.getId()), node));
        }
        SetupRules.Result result = SetupRules.evaluate(reads, mapper);
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        store.persist(clusterId, reads, result, Instant.now(), millis);
        hub.publish(clusterId, TOPIC);
    }

    @Transactional
    public SetupReviewView accept(UUID clusterId, AcceptRiskRequest request) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_WRITE);
        FindingKey key = new FindingKey(clusterId, request.code(), request.subject());
        SetupFindingEntity finding =
                findings.findById(key).orElseThrow(() -> new NotFoundException("setup finding", request.code()));
        Instant now = Instant.now();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt: must be in the future");
        }
        var actor = actorResolver.resolve();
        AuditEvent event = audit.begin(
                actor,
                "ACCEPT_SETUP_RISK",
                "SETUP_FINDING",
                finding.getCode() + " on " + finding.getSubject(),
                clusterId,
                null,
                request.expiresAt() == null
                        ? Map.of(REASON, request.reason())
                        : Map.of(
                                REASON,
                                request.reason(),
                                "expiresAt",
                                request.expiresAt().toString()),
                false);
        acceptances.save(new SetupFindingAcceptanceEntity(
                clusterId,
                request.code(),
                request.subject(),
                request.reason().trim(),
                actor.displayName(),
                now,
                request.expiresAt()));
        audit.succeed(event, 1);
        hub.publish(clusterId, TOPIC);
        return render(clusterId, null);
    }

    @Transactional
    public SetupReviewView revoke(UUID clusterId, String code, String subject) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_WRITE);
        FindingKey key = new FindingKey(clusterId, code, subject);
        SetupFindingAcceptanceEntity acceptance =
                acceptances.findById(key).orElseThrow(() -> new NotFoundException("risk acceptance", code));
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "REVOKE_SETUP_RISK",
                "SETUP_FINDING",
                code + " on " + subject,
                clusterId,
                null,
                Map.of("acceptedBy", acceptance.getAcceptedBy()),
                false);
        acceptances.delete(acceptance);
        audit.succeed(event, 1);
        hub.publish(clusterId, TOPIC);
        return render(clusterId, null);
    }

    private SetupReviewView render(UUID clusterId, String notice) {
        SetupReviewEntity review = reviews.findById(clusterId).orElse(null);
        Map<String, String> labels = new HashMap<>();
        for (ClusterNode node : clusters.nodes(clusterId)) {
            labels.put("node:" + node.getId(), node.getName());
        }
        labels.put(SetupRules.CLUSTER, "cluster");

        Map<FindingKey, SetupFindingAcceptanceEntity> accepted = new HashMap<>();
        for (SetupFindingAcceptanceEntity a : acceptances.findByClusterId(clusterId)) {
            accepted.put(new FindingKey(clusterId, a.getCode(), a.getSubject()), a);
        }
        Instant now = Instant.now();
        List<SetupFindingView> views = new ArrayList<>();
        Tally tally = new Tally();
        for (SetupFindingEntity row : findings.findByClusterId(clusterId)) {
            Finding f = mapper.readValue(row.getFinding(), Finding.class);
            AcceptanceView acceptance =
                    acceptanceView(accepted.get(new FindingKey(clusterId, row.getCode(), row.getSubject())), now);
            tally.count(f.severity(), acceptance != null && acceptance.active());
            views.add(findingView(f, row, review, labels, acceptance));
        }
        views.sort(Comparator.comparing((SetupFindingView v) -> Severity.valueOf(v.severity()))
                .thenComparing(SetupFindingView::category)
                .thenComparing(SetupFindingView::code)
                .thenComparing(SetupFindingView::subjectLabel));

        List<ReviewedNodeView> nodes = new ArrayList<>();
        List<NotAssessedView> notAssessed = new ArrayList<>();
        if (review != null) {
            nodes.addAll(reviewedNodes(review));
            notAssessed.addAll(notAssessedViews(review, labels));
        }
        return new SetupReviewView(
                clusterId,
                review == null ? null : review.getReviewedAt(),
                review == null ? 0 : review.getDurationMs(),
                review == null ? 0 : review.getNodesTotal(),
                review == null ? 0 : review.getNodesReviewed(),
                review != null && review.isClusterEvaluated(),
                nodes,
                views,
                notAssessed,
                new SeverityCountsView(tally.critical, tally.warning, tally.info),
                tally.accepted,
                SetupRules.CODES.size(),
                notice);
    }

    /** How many findings stand at each severity, and how many an active acceptance has set aside. */
    private static final class Tally {
        int critical;
        int warning;
        int info;
        int accepted;

        void count(Severity severity, boolean acceptedActive) {
            if (acceptedActive) {
                accepted++;
                return;
            }
            switch (severity) {
                case CRITICAL -> critical++;
                case WARNING -> warning++;
                case INFO -> info++;
            }
        }
    }

    private static AcceptanceView acceptanceView(SetupFindingAcceptanceEntity a, Instant now) {
        if (a == null) {
            return null;
        }
        return new AcceptanceView(
                a.getReason(), a.getAcceptedBy(), a.getCreatedAt(), a.getExpiresAt(), a.activeAt(now));
    }

    private static SetupFindingView findingView(
            Finding f,
            SetupFindingEntity row,
            SetupReviewEntity review,
            Map<String, String> labels,
            AcceptanceView acceptance) {
        return new SetupFindingView(
                f.code(),
                f.category().name(),
                f.severity().name(),
                f.subject(),
                labels.getOrDefault(f.subject(), f.subject()),
                f.title(),
                f.impact(),
                f.evidence().stream()
                        .map(e -> new EvidenceView(e.node(), e.key(), e.value()))
                        .toList(),
                f.recommendation(),
                f.snippet(),
                f.caveats(),
                f.appliable(),
                row.getFirstSeenAt(),
                row.getLastSeenAt(),
                review != null && row.getLastSeenAt().isBefore(review.getReviewedAt()),
                acceptance);
    }

    private List<ReviewedNodeView> reviewedNodes(SetupReviewEntity review) {
        return mapper.readValue(review.getNodes(), new TypeReference<List<Map<String, Object>>>() {}).stream()
                .map(n -> new ReviewedNodeView(
                        UUID.fromString(String.valueOf(n.get("nodeId"))),
                        String.valueOf(n.get("nodeName")),
                        Boolean.TRUE.equals(n.get("live")),
                        Boolean.TRUE.equals(n.get("reviewed")),
                        n.get(REASON) == null ? null : String.valueOf(n.get(REASON))))
                .toList();
    }

    private List<NotAssessedView> notAssessedViews(SetupReviewEntity review, Map<String, String> labels) {
        return mapper.readValue(review.getNotAssessed(), new TypeReference<List<NotAssessed>>() {}).stream()
                .map(na -> new NotAssessedView(
                        na.code(), na.subject(), labels.getOrDefault(na.subject(), na.subject()), na.reason()))
                .toList();
    }
}
