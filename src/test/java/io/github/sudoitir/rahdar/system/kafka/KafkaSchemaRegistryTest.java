package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.BrokerSystem;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.access.Assessment;
import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.openbroker.kafka.schema.Subject;
import io.github.sudoitir.openbroker.kafka.schema.Subjects;
import io.github.sudoitir.openbroker.messaging.Cursor;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A registry that is missing, down or unimplemented is unavailable, and a filter never drops the cursor. */
class KafkaSchemaRegistryTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterConnection connection = mock(ClusterConnection.class);
    private final SchemaCatalog catalog = mock(SchemaCatalog.class);
    private final KafkaConnectionRepository connections = mock(KafkaConnectionRepository.class);

    KafkaSchemaRegistryTest() {
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.unwrap(SchemaCatalog.class)).thenReturn(Optional.of(catalog));
        when(catalog.capability()).thenReturn(Assessment.available("ready"));
        when(connections.findById(CLUSTER))
                .thenReturn(Optional.of(new KafkaConnectionEntity(
                        CLUSTER, "k:9092", "PLAINTEXT", null, null, "confluent", "https://r", null)));
    }

    private KafkaSchemaRegistry registry() {
        return new KafkaSchemaRegistry(gateway, connections, mock(ClusterAccessGuard.class));
    }

    @Test
    void aFilterKeepsMatchingSubjectsAndTheRegistrysCursor() {
        Subject orders =
                new Subject("orders-value", List.of(), Optional.of(CompatibilityLevel.BACKWARD), Optional.empty());
        Subject payments = new Subject("payments-value", List.of(), Optional.empty(), Optional.empty());
        when(catalog.subjects(new Cursor("c1")))
                .thenReturn(new Subjects(List.of(orders, payments), Optional.of(new Cursor("next"))));

        Subjects filtered = registry().subjects(CLUSTER, "c1", "orders");
        Subjects all = registry().subjects(CLUSTER, "c1", "  ");

        assertThat(filtered.items()).extracting(Subject::name).containsExactly("orders-value");
        assertThat(filtered.next()).map(Cursor::token).contains("next");
        assertThat(all.items()).extracting(Subject::name).containsExactly("orders-value", "payments-value");
    }

    @Test
    void aSubjectReadIsTheRegistrysSubject() {
        Subject subject = new Subject("orders-value", List.of(), Optional.empty(), Optional.of("set"));
        when(catalog.subject("orders-value")).thenReturn(subject);

        assertThat(registry().subject(CLUSTER, "orders-value")).isSameAs(subject);
    }

    @Test
    void aSystemWithNoRegistryIsUnavailable() {
        when(connection.unwrap(SchemaCatalog.class)).thenReturn(Optional.empty());
        when(connection.system()).thenReturn(new BrokerSystem("kafka", "Kafka", "3", 1, Set.of(), List.of()));

        assertThatThrownBy(() -> registry().open(CLUSTER))
                .isInstanceOf(SchemaUnavailableException.class)
                .hasMessage("This system has no schema registry.");
        when(connection.unwrap(SchemaCatalog.class)).thenReturn(Optional.of(catalog));
    }

    @Test
    void anAvailableCatalogIsReturned() {
        assertThat(registry().catalog(CLUSTER)).isSameAs(catalog);
    }

    @Test
    void anUnreachableOrUnimplementedRegistryStaysTheDriversReason() {
        when(catalog.subjects(any()))
                .thenThrow(broker(Status.UNAVAILABLE, "registry down"))
                .thenThrow(broker(Status.UNIMPLEMENTED, "not a schema registry"));

        assertThatThrownBy(() -> registry().subjects(CLUSTER, null, null))
                .isInstanceOf(SchemaUnavailableException.class)
                .hasMessage("registry down");
        assertThatThrownBy(() -> registry().subjects(CLUSTER, null, null))
                .isInstanceOf(SchemaUnavailableException.class)
                .hasMessage("not a schema registry");
    }

    @Test
    void aMissingConnectionIsNotFoundAndANotFoundSubjectStaysNotFound() {
        UUID missing = UUID.randomUUID();
        when(connections.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry().kind(missing)).isInstanceOf(NotFoundException.class);
        assertThat(KafkaSchemaRegistry.translate("confluent", broker(Status.NOT_FOUND, "missing subject")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("missing subject");
        assertThatThrownBy(() -> KafkaSchemaRegistry.call("confluent", () -> {
                    throw broker(Status.UNAVAILABLE, "down");
                }))
                .isInstanceOf(SchemaUnavailableException.class)
                .hasMessage("down");
    }

    private static BrokerException broker(Status status, String message) {
        return new BrokerException(status, ErrorInfo.of("reason"), Dispatch.REJECTED, null, message);
    }
}
