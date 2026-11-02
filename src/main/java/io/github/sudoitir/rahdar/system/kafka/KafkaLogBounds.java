package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.LogBounds;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A topic partition's log start and end offsets, from the driver's partition listing. */
@Component
@RequiredArgsConstructor
class KafkaLogBounds implements LogBounds {

    private static final String LOG_START_OFFSET = "kafka.log_start_offset";
    private static final String LOG_END_OFFSET = "kafka.log_end_offset";

    private final BrokerGateway gateway;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public Bounds of(UUID clusterId, ResourceId log, int partition) {
        try {
            Resource found = gateway
                    .cluster(clusterId)
                    .resources()
                    .read(ResourceQuery.of(KindId.of("kafka.partition"))
                            .where(Map.of("topic", log.name()))
                            .named(String.valueOf(partition))
                            .project(Set.of(LOG_START_OFFSET, LOG_END_OFFSET)))
                    .orThrow()
                    .items()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("partition", log.name() + "/" + partition));
            return new Bounds(number(found, LOG_START_OFFSET), number(found, LOG_END_OFFSET));
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    @Override
    public SortedMap<Integer, Bounds> partitions(UUID clusterId, ResourceId log) {
        try {
            SortedMap<Integer, Bounds> found = new TreeMap<>();
            Optional<String> next = Optional.empty();
            do {
                ResourceQuery query = ResourceQuery.of(KindId.of("kafka.partition"))
                        .where(Map.of("topic", log.name()))
                        .project(Set.of(LOG_START_OFFSET, LOG_END_OFFSET));
                var page = gateway.cluster(clusterId)
                        .resources()
                        .read(next.map(query::continueFrom).orElse(query))
                        .orThrow();
                for (Resource partition : page.items()) {
                    found.put(
                            Integer.parseInt(partition.id().name()),
                            new Bounds(number(partition, LOG_START_OFFSET), number(partition, LOG_END_OFFSET)));
                }
                next = page.continueToken();
            } while (next.isPresent());
            if (found.isEmpty()) {
                throw new NotFoundException("topic", log.name());
            }
            return found;
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    private static long number(Resource resource, String attribute) {
        Object value =
                resource.attributes().raw(attribute).orElseThrow(() -> new NotFoundException("offset", attribute));
        return value instanceof MetricValue metric ? metric.asLong() : Long.parseLong(String.valueOf(value));
    }
}
