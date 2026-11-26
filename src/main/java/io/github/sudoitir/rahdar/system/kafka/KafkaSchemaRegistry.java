package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.access.Assessment;
import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The schema catalog of one Kafka cluster. A registry that is not configured, or that refuses an operation, is a
 * {@link SchemaUnavailableException}, not a 500. Unknown is not unavailable: a named registry that has not been
 * reached yet is asked.
 */
@Service
@RequiredArgsConstructor
public class KafkaSchemaRegistry {

    private final BrokerGateway gateway;
    private final KafkaConnectionRepository connections;

    public SchemaCatalog open(UUID clusterId) {
        ClusterConnection connection = gateway.cluster(clusterId);
        return connection
                .unwrap(SchemaCatalog.class)
                .orElseThrow(() -> new SchemaUnavailableException(
                        connection.system().id(), "This system has no schema registry."));
    }

    /** The catalog, after refusing one whose capability is unavailable. */
    public SchemaCatalog catalog(UUID clusterId) {
        SchemaCatalog catalog = open(clusterId);
        Assessment assessment = catalog.capability();
        if (assessment.status() == Assessment.Status.UNAVAILABLE) {
            throw new SchemaUnavailableException(kind(clusterId), assessment.reason());
        }
        return catalog;
    }

    public <T> T call(UUID clusterId, Function<SchemaCatalog, T> call) {
        String system = kind(clusterId);
        try {
            return call.apply(catalog(clusterId));
        } catch (BrokerException e) {
            throw translate(system, e);
        }
    }

    public String kind(UUID clusterId) {
        return connections
                .findById(clusterId)
                .map(KafkaConnectionEntity::getRegistryKind)
                .orElseThrow(() -> new NotFoundException("cluster", clusterId));
    }

    static RuntimeException translate(String system, BrokerException e) {
        if (e.status() == Status.UNAVAILABLE || e.status() == Status.UNIMPLEMENTED) {
            return new SchemaUnavailableException(system, e.getMessage());
        }
        return KafkaFailures.translate(e);
    }

    static <T> T call(String system, Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            throw translate(system, e);
        }
    }
}
