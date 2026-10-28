package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRow;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * What resetting a group's offsets would do, partition by partition (ADR-0216). The preview is the driver's, widened
 * with what an operator needs to decide: the records skipped or read again, a time past the end of the log, an offset
 * outside a partition's range, and why the reset is refused now (members, a rebalance, a streams application).
 */
@Service
@RequiredArgsConstructor
public class KafkaOffsets {

    private static final Set<String> TARGETS = Set.of("earliest", "latest", "time", "offset");

    /** A preview, and what the run needs of it: the driver's state key and position for each topic it moves. */
    record Plan(
            OffsetPreview view,
            Map<String, ResourceId> ids,
            Map<String, String> etags,
            Map<String, Position> positions) {}

    private record Bounds(long start, long end) {}

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public OffsetPreview preview(UUID clusterId, String group, OffsetRequest request) {
        return plan(clusterId, group, request).view();
    }

    Plan plan(UUID clusterId, String group, OffsetRequest request) {
        access.requireResource(clusterId, ResourceRef.subscription(group), ResourcePermissions.SUBSCRIPTION_SEEK);
        String to = request.to() == null ? "" : request.to().toLowerCase(Locale.ROOT);
        if (!TARGETS.contains(to)) {
            throw new IllegalArgumentException("Reset to earliest, latest, time or offset.");
        }
        ClusterConnection connection = gateway.cluster(clusterId);
        Resource groupResource = KafkaGroups.group(connection, group);
        KindId kind = KafkaGroups.subscriptionKind(groupResource, KafkaGroups.kindsOf(connection));
        String readOnly = KafkaGroups.readOnly(groupResource);
        if (kind == null) {
            throw new ConflictException(
                    "read-only",
                    readOnly != null ? readOnly : "This cluster does not manage the offsets of this kind of group.");
        }
        List<Resource> subscriptions = KafkaGroups.read(
                        connection, ResourceQuery.of(kind).where(Map.of("group", group)))
                .items();
        Set<String> topics = chosen(request, subscriptions);
        Instant time = "time".equals(to) ? time(request.value()) : null;
        Long offset = "offset".equals(to) ? offset(request.value()) : null;

        List<OffsetRow> rows = new ArrayList<>();
        Set<String> members = new LinkedHashSet<>();
        Map<String, ResourceId> ids = new LinkedHashMap<>();
        Map<String, String> etags = new LinkedHashMap<>();
        Map<String, Position> positions = new LinkedHashMap<>();
        for (String topic : topics) {
            Resource subscription = subscriptions.stream()
                    .filter(s -> s.id().name().equals(topic))
                    .findFirst()
                    .orElse(null);
            Map<Integer, Bounds> bounds = bounds(connection, topic);
            if (bounds.isEmpty()) {
                throw new IllegalArgumentException("Topic " + topic + " does not exist.");
            }
            Map<Integer, Long> current = committed(subscription);
            Position position = position(to, time, offset, bounds, topic, rows);
            if (position == null) {
                continue;
            }
            ResourceId id = subscription != null ? subscription.id() : idOf(kind, group, topic);
            Preview preview = KafkaFailures.call(
                    () -> connection.operations().preview(id, new Operation.Seek(position), Scope.whole()));
            ids.put(topic, id);
            etags.put(topic, preview.etag());
            positions.put(topic, position);
            for (AttributeChange change : preview.diff()) {
                if (change.key().equals(KafkaKinds.MEMBERS)) {
                    change.before().ifPresent(l -> ((List<?>) l).forEach(m -> members.add(String.valueOf(m))));
                } else if (change.key().startsWith(KafkaKinds.OFFSETS + ".")) {
                    int partition = Integer.parseInt(change.key().substring(KafkaKinds.OFFSETS.length() + 1));
                    rows.add(row(topic, partition, current.get(partition), change, time, bounds.get(partition)));
                }
            }
        }
        rows.sort(Comparator.comparing(OffsetRow::topic).thenComparingInt(OffsetRow::partition));
        String blocked = blocked(groupResource, members);
        List<String> etagParts = new ArrayList<>(etags.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList());
        rows.stream().filter(r -> r.refused() != null).forEach(r -> etagParts.add(r.topic() + "/" + r.partition()));
        return new Plan(
                new OffsetPreview(List.copyOf(rows), List.copyOf(members), blocked, KafkaEtags.of(etagParts)),
                ids,
                etags,
                positions);
    }

    private static ResourceId idOf(KindId kind, String group, String topic) {
        return new ResourceId(kind, ResourcePath.of(group, topic));
    }

    private static Set<String> chosen(OffsetRequest request, List<Resource> subscriptions) {
        Set<String> topics = new LinkedHashSet<>();
        if (request.topics() != null && !request.topics().isEmpty()) {
            topics.addAll(request.topics());
        } else {
            subscriptions.forEach(s -> topics.add(s.id().name()));
        }
        return topics;
    }

    /** The position for the topic; for an offset, only the partitions in range (the rest are refused rows). */
    private static Position position(
            String to, Instant time, Long offset, Map<Integer, Bounds> bounds, String topic, List<OffsetRow> rows) {
        return switch (to) {
            case "earliest" -> Position.earliest();
            case "latest" -> Position.latest();
            case "time" -> new Position.AtTime(time);
            default -> {
                Map<Shard, Long> valid = new TreeMap<>(Comparator.comparing(Object::toString));
                bounds.forEach((partition, range) -> {
                    if (offset < range.start() || offset > range.end()) {
                        rows.add(new OffsetRow(
                                topic,
                                partition,
                                null,
                                null,
                                0,
                                0,
                                null,
                                "Offset " + offset + " is outside partition " + partition + ", which holds "
                                        + range.start() + " to " + range.end() + "."));
                    } else {
                        valid.put(Shard.partition(PartitionId.of(String.valueOf(partition))), offset);
                    }
                });
                yield valid.isEmpty() ? null : new Position.AtOffsets(valid);
            }
        };
    }

    private static OffsetRow row(
            String topic, int partition, Long current, AttributeChange change, Instant time, Bounds bounds) {
        Long next = change.after().map(v -> ((Number) v).longValue()).orElse(null);
        long skipped = current != null && next != null ? Math.max(0, next - current) : 0;
        long reread = current != null && next != null ? Math.max(0, current - next) : 0;
        String note = null;
        if (current == null) {
            note = "The group has no offset on this partition yet.";
        } else if (time != null && bounds != null && next != null && next == bounds.end()) {
            note = "No record is at or after " + time + ", so the offset moves to the end of the partition.";
        }
        return new OffsetRow(topic, partition, current, next, skipped, reread, note, null);
    }

    private static String blocked(Resource group, Set<String> members) {
        String state = KafkaGroups.text(group, KafkaKinds.GROUP_STATE).toLowerCase(Locale.ROOT);
        if (!members.isEmpty()) {
            return "The group has " + members.size() + " active member" + (members.size() == 1 ? "" : "s") + ": "
                    + String.join(", ", members) + ". Stop them first.";
        }
        if (state.contains("rebalanc") || state.equals("assigning") || state.equals("reconciling")) {
            return "The group is rebalancing (" + state + "). Try again once it settles.";
        }
        return null;
    }

    private static Map<Integer, Long> committed(Resource subscription) {
        Map<Integer, Long> out = new TreeMap<>();
        if (subscription != null
                && subscription.attributes().raw(KafkaKinds.OFFSETS).orElse(null) instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(Integer.parseInt(String.valueOf(k)), ((Number) v).longValue()));
        }
        return out;
    }

    private static Map<Integer, Bounds> bounds(ClusterConnection connection, String topic) {
        Map<Integer, Bounds> out = new TreeMap<>();
        for (Resource p : KafkaGroups.read(
                        connection,
                        ResourceQuery.of(KafkaKinds.PARTITION)
                                .where(Map.of("topic", topic))
                                .project(Set.of(KafkaKinds.LOG_START_OFFSET, KafkaKinds.LOG_END_OFFSET)))
                .items()) {
            long start =
                    ((Number) p.attributes().raw(KafkaKinds.LOG_START_OFFSET).orElse(0L)).longValue();
            long end = ((Number) p.attributes().raw(KafkaKinds.LOG_END_OFFSET).orElse(0L)).longValue();
            out.put(Integer.parseInt(p.id().name()), new Bounds(start, end));
        }
        return out;
    }

    private static Instant time(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("A time is an ISO-8601 instant, such as 2026-10-28T09:00:00Z.");
        }
    }

    private static Long offset(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("An offset is a whole number.");
        }
    }
}
