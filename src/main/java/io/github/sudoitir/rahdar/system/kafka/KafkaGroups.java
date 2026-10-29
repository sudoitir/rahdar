package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog.Accuracy;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupDetail;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPartitionLag;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupTopic;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Consumer groups, classic, consumer, share and streams, with their lag as an estimate and, per partition, where each
 * group stands (ADR-0216). Reads go through the driver's resources: a group's lag is its subscriptions' backlog.
 */
@Service
@RequiredArgsConstructor
public class KafkaGroups {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final String NO_OFFSETS = "The group has no committed offsets, so its lag is not known.";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final KafkaAdminReads admin;
    private final QueueSnapshots snapshots;

    public GroupPage list(UUID clusterId, String type, String state, String q, String cursor, Integer limit) {
        access.requireCluster(clusterId, Permissions.SUBSCRIPTION_READ);
        ClusterConnection connection = gateway.cluster(clusterId);
        Map<String, String> selector = new LinkedHashMap<>();
        if (type != null && !type.isBlank()) {
            selector.put("type", type);
        }
        if (state != null && !state.isBlank()) {
            selector.put("state", state);
        }
        ResourceQuery query = ResourceQuery.of(KafkaKinds.GROUP).where(selector).limit(pageSize(limit));
        if (q != null && !q.isBlank()) {
            query = query.named("*" + q + "*");
        }
        if (cursor != null && !cursor.isBlank()) {
            query = query.continueFrom(cursor);
        }
        ResourcePage page = read(connection, query);
        // One listing of the groups answers the page; their lag is the scrape's last sample, read from the snapshot
        // beside it, so the page costs one broker round however many groups it holds and never one per row.
        Map<String, Backlog> lagByGroup = lagOf(clusterId, page.items());
        List<GroupRow> rows = page.items().stream()
                .map(g -> row(g, lagByGroup.get(g.id().name())))
                .toList();
        return new GroupPage(rows, page.continueToken().orElse(null));
    }

    public GroupDetail detail(UUID clusterId, String id) {
        access.requireCluster(clusterId, Permissions.SUBSCRIPTION_READ);
        ClusterConnection connection = gateway.cluster(clusterId);
        Resource group = group(connection, id);
        KindId kind = subscriptionKind(group, kindsOf(connection));
        List<GroupTopic> topics = new ArrayList<>();
        if (kind != null) {
            List<Resource> subscriptions = read(
                            connection, ResourceQuery.of(kind).where(Map.of("group", id)))
                    .items();
            // A subscription carries each partition's committed offset and lag, and the end is their sum, so a group of
            // many topics costs no read per topic.
            for (Resource subscription : subscriptions) {
                List<BacklogBreakdown.PartitionLag> partitions =
                        KafkaBacklogBreakdown.partitions(subscription.attributes(), Map.of());
                topics.add(new GroupTopic(
                        subscription.id().name(),
                        partitions.isEmpty(),
                        partitions.stream()
                                .map(p -> new GroupPartitionLag(
                                        p.partition(), p.committed(), p.end(), p.lag(), p.reason()))
                                .toList()));
            }
        }
        return new GroupDetail(
                id,
                text(group, KafkaKinds.GROUP_TYPE),
                text(group, KafkaKinds.GROUP_STATE),
                readOnly(group),
                topics,
                "streams".equals(text(group, KafkaKinds.GROUP_TYPE))
                        ? List.of()
                        : admin.members(clusterId, id, "share".equals(text(group, KafkaKinds.GROUP_TYPE))),
                Instant.now());
    }

    /** The group's resource; absent groups are not found. */
    static Resource group(ClusterConnection connection, String id) {
        return read(connection, ResourceQuery.of(KafkaKinds.GROUP).named(escape(id))).items().stream()
                .filter(g -> g.id().name().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("group", id));
    }

    /** The kind of subscription a group's offsets are read through, or null where they are not (a streams group). */
    static KindId subscriptionKind(Resource group, Set<KindId> kinds) {
        if (readOnly(group) != null) {
            return null;
        }
        KindId kind = "share".equals(text(group, KafkaKinds.GROUP_TYPE))
                ? KafkaKinds.SHARE_GROUP_TOPIC
                : KafkaKinds.GROUP_TOPIC;
        return kinds.contains(kind) ? kind : null;
    }

    static Set<KindId> kindsOf(ClusterConnection connection) {
        return Set.copyOf(
                connection.resources().kinds().stream().map(k -> k.id()).toList());
    }

    static ResourcePage read(ClusterConnection connection, ResourceQuery query) {
        return KafkaFailures.call(() -> connection.resources().read(query).orThrow());
    }

    static String text(Resource resource, String attribute) {
        return resource.attributes().raw(attribute).map(String::valueOf).orElse("unknown");
    }

    static String readOnly(Resource group) {
        return group.attributes()
                .raw(KafkaKinds.READ_ONLY_REASON)
                .map(String::valueOf)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private static GroupRow row(Resource group, Backlog lag) {
        String readOnly = readOnly(group);
        Backlog shown = lag != null
                ? lag
                : new Backlog(null, Accuracy.UNAVAILABLE, readOnly != null ? readOnly : NO_OFFSETS, false);
        long members = group.attributes()
                .raw(KafkaKinds.MEMBERS)
                .map(v -> ((Number) v).longValue())
                .orElse(0L);
        return new GroupRow(
                group.id().name(),
                text(group, KafkaKinds.GROUP_TYPE),
                text(group, KafkaKinds.GROUP_STATE),
                members,
                shown,
                readOnly);
    }

    /**
     * The lag of each group that has a subscription kind, summed over its topics from the last scrape: an estimate
     * with the scrape's own reason, or unavailable for a group the scrape has not sampled (a new group, or one with no
     * committed offsets).
     */
    private Map<String, Backlog> lagOf(UUID clusterId, List<Resource> groups) {
        Set<String> wanted = new HashSet<>();
        groups.forEach(g -> wanted.add(g.id().name()));
        Map<String, Long> sums = new HashMap<>();
        Map<String, Integer> unknown = new HashMap<>();
        Map<String, Integer> rowCount = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        for (QueueSnapshot row : snapshots.forCluster(clusterId)) {
            String path = row.attributes().get(QueueRow.PATH);
            if (path == null || row.kind() == null || !row.kind().startsWith(KafkaModule.SYSTEM + ".")) {
                continue;
            }
            String group = path.substring(0, path.indexOf('/'));
            if (wanted.contains(group)) {
                rowCount.merge(group, 1, Integer::sum);
                if (row.backlogKnown()) {
                    sums.merge(group, row.messageCount(), Long::sum);
                    reasons.putIfAbsent(group, row.attributes().get(QueueRow.BACKLOG_REASON));
                } else {
                    unknown.merge(group, 1, Integer::sum);
                }
            }
        }
        Map<String, Backlog> out = new TreeMap<>();
        rowCount.forEach((group, rows) -> {
            int missing = unknown.getOrDefault(group, 0);
            if (missing == rows) {
                out.put(
                        group,
                        new Backlog(
                                null,
                                Accuracy.UNAVAILABLE,
                                "No topic of the group has a committed offset, so it has no lag to report.",
                                false));
            } else if (missing > 0) {
                out.put(
                        group,
                        new Backlog(
                                sums.get(group),
                                Accuracy.ESTIMATE,
                                "Excludes " + missing + " of " + rows + " topics with no committed offset.",
                                true));
            } else {
                out.put(group, new Backlog(sums.get(group), Accuracy.ESTIMATE, reasons.get(group), true));
            }
        });
        return out;
    }

    private static int pageSize(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /** A name as a glob that matches only it. */
    static String escape(String name) {
        return name.replace("*", "?");
    }
}
