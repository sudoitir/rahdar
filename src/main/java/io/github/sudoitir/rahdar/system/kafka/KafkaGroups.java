package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
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
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupDetail;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupTopic;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.PartitionLag;
import java.time.Instant;
import java.util.ArrayList;
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
    private final KafkaGroupMembers members;

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
        Set<KindId> kinds = kindsOf(connection);
        List<ResourceQuery> lagQueries = new ArrayList<>();
        List<Resource> measured = new ArrayList<>();
        for (Resource group : page.items()) {
            KindId kind = subscriptionKind(group, kinds);
            if (kind != null) {
                measured.add(group);
                lagQueries.add(
                        ResourceQuery.of(kind).where(Map.of("group", group.id().name())));
            }
        }
        List<ResourcePage> lags = lagQueries.isEmpty()
                ? List.of()
                : KafkaFailures.call(
                        () -> connection.resources().read(lagQueries).orThrow());
        Map<String, Backlog> lagByGroup = new TreeMap<>();
        for (int i = 0; i < measured.size(); i++) {
            lagByGroup.put(measured.get(i).id().name(), total(lags.get(i).items()));
        }
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
            List<ResourceQuery> endQueries = subscriptions.stream()
                    .map(s -> ResourceQuery.of(KafkaKinds.PARTITION)
                            .where(Map.of("topic", s.id().name()))
                            .project(Set.of(KafkaKinds.LOG_END_OFFSET)))
                    .toList();
            List<ResourcePage> ends = endQueries.isEmpty()
                    ? List.of()
                    : KafkaFailures.call(
                            () -> connection.resources().read(endQueries).orThrow());
            for (int i = 0; i < subscriptions.size(); i++) {
                Resource subscription = subscriptions.get(i);
                Map<Integer, Long> end = new TreeMap<>();
                for (Resource partition : ends.get(i).items()) {
                    partition
                            .attributes()
                            .raw(KafkaKinds.LOG_END_OFFSET)
                            .ifPresent(
                                    v -> end.put(Integer.parseInt(partition.id().name()), ((Number) v).longValue()));
                }
                List<BacklogBreakdown.PartitionLag> partitions =
                        KafkaBacklogBreakdown.partitions(subscription.attributes(), end);
                topics.add(new GroupTopic(
                        subscription.id().name(),
                        end.isEmpty(),
                        partitions.stream()
                                .map(p -> new PartitionLag(p.partition(), p.committed(), p.end(), p.lag(), p.reason()))
                                .toList()));
            }
        }
        return new GroupDetail(
                id,
                text(group, KafkaKinds.GROUP_TYPE),
                text(group, KafkaKinds.GROUP_STATE),
                readOnly(group),
                topics,
                members.of(connection, group, kind),
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

    /** A group's lag over its topics: the sum of the backlogs that are known, an estimate, or unavailable. */
    private static Backlog total(List<Resource> subscriptions) {
        long sum = 0;
        boolean any = false;
        String reason = null;
        for (Resource s : subscriptions) {
            MetricValue backlog = s.attributes().get(MetricKeys.BACKLOG).orElse(null);
            if (backlog != null && !backlog.breakdown().isEmpty()) {
                any = true;
                sum += backlog.asLong();
            }
            reason = Backlog.of(s).reason();
        }
        return any
                ? new Backlog(sum, Accuracy.ESTIMATE, reason, true)
                : new Backlog(null, Accuracy.UNAVAILABLE, NO_OFFSETS, false);
    }

    private static int pageSize(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /** A name as a glob that matches only it. */
    static String escape(String name) {
        return name.replace("*", "?");
    }
}
