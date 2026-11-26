package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.access.Assessment;
import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.openbroker.kafka.schema.Subject;
import io.github.sudoitir.openbroker.kafka.schema.Subjects;
import io.github.sudoitir.openbroker.messaging.Cursor;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
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
    private final ClusterAccessGuard access;

    /** A page of subjects. The filter applies to this page; the cursor is the registry's. */
    public Subjects subjects(UUID clusterId, String cursor, String filter) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_READ);
        Subjects page = call(clusterId, catalog -> catalog.subjects(new Cursor(cursor == null ? "" : cursor)));
        if (filter == null || filter.isBlank()) {
            return page;
        }
        String needle = filter;
        return new Subjects(
                page.items().stream()
                        .filter(subject -> subject.name().contains(needle))
                        .toList(),
                page.next());
    }

    public Subject subject(UUID clusterId, String name) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_READ);
        return call(clusterId, catalog -> catalog.subject(name));
    }

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

    /** A permanent delete that stopped after the soft-delete. The subject may already be gone. */
    static final String DELETE_IN_DOUBT = "schema-delete-in-doubt";

    static RuntimeException translate(String system, BrokerException e) {
        if (e.dispatch() == Dispatch.IN_DOUBT) {
            return new ConflictException(
                    DELETE_IN_DOUBT,
                    "The subject was soft-deleted, and the permanent delete did not finish. The state is in doubt.",
                    e);
        }
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
