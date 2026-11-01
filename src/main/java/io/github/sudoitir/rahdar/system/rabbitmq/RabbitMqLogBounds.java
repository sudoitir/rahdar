package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.BrowseRequest;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.LogBounds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A stream is one log, read as partition 0: it starts at the first message still kept, found by a one-message browse
 * from the earliest, and ends after the committed offset the stream reports. A RabbitMQ that reports no committed
 * offset (4.x) ends it at the start plus the messages the stream holds, which the management statistics sample.
 */
@Component
@RequiredArgsConstructor
class RabbitMqLogBounds implements LogBounds {

    private static final KindId STREAM = KindId.of("rabbitmq.stream");
    private static final String COMMITTED_OFFSET = "rabbitmq.committed_offset";
    private static final PartitionId ONLY_PARTITION = PartitionId.of("0");

    private final BrokerGateway gateway;

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    @Override
    public Set<KindId> logs(UUID clusterId) {
        return Set.of(STREAM);
    }

    @Override
    public Bounds of(UUID clusterId, ResourceId log, int partition) {
        if (partition != 0) {
            throw new IllegalArgumentException("A stream is one log, partition 0, not partition " + partition + ".");
        }
        try {
            ClusterConnection cluster = gateway.cluster(clusterId);
            Resource stream = stream(cluster, log);
            Optional<Long> start = start(cluster, log);
            long end = end(stream, start);
            long first = start.orElse(end);
            return new Bounds(Math.min(first, end), end);
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    private static Resource stream(ClusterConnection cluster, ResourceId log) {
        List<String> segments = log.path().segments();
        ResourceQuery query = ResourceQuery.of(STREAM)
                .where(Map.of("vhost", segments.getFirst()))
                .named(log.name())
                .project(Set.of(COMMITTED_OFFSET, MetricKeys.BACKLOG.name()));
        return cluster.resources().read(query).orThrow().items().stream()
                .filter(r -> r.id().equals(log))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("stream", log.name()));
    }

    private static long end(Resource found, Optional<Long> start) {
        Optional<Long> committed = found.attributes().raw(COMMITTED_OFFSET).map(RabbitMqLogBounds::number);
        if (committed.isPresent()) {
            return committed.get() + 1;
        }
        // No committed offset: the start plus what the stream holds; a stream that holds nothing ends where it starts.
        long held = found.attributes()
                .get(MetricKeys.BACKLOG)
                .map(MetricValue::asLong)
                .orElse(0L);
        return start.map(first -> first + held).orElse(0L);
    }

    private static Optional<Long> start(ClusterConnection cluster, ResourceId log) {
        return cluster
                .messages()
                .browse(
                        log,
                        Scope.shards(Set.of(Shard.partition(ONLY_PARTITION))),
                        new BrowseRequest(new Position.Earliest(), 1, Optional.empty(), 0))
                .messages()
                .stream()
                .findFirst()
                .map(message -> Long.parseLong(message.ref().position()));
    }

    private static long number(Object value) {
        return value instanceof MetricValue metric ? metric.asLong() : Long.parseLong(String.valueOf(value));
    }
}
