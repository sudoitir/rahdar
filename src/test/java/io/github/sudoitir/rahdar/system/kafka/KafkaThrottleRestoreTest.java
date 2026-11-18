package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A stored throttle is put back when the move is gone, including a row left behind by a restart, and only once. */
class KafkaThrottleRestoreTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Operations operations = mock(Operations.class);
    private final Resources resources = mock(Resources.class);
    private final KafkaReassignmentRepository repository = mock(KafkaReassignmentRepository.class);
    private final KafkaThrottleRestore restore;

    KafkaThrottleRestoreTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(connection.resources()).thenReturn(resources);
        when(operations.execute(any(), any(), any(), any()))
                .thenReturn(Partial.complete(OperationResult.done(), List.of()));
        restore = new KafkaThrottleRestore(gateway, repository);
    }

    @Test
    void aFinishedMoveRemovesKeysItAddedAndRestoresAPreExistingRate() {
        KafkaReassignmentEntity row = row();
        List<KafkaReassignmentEntity> stored = new ArrayList<>(List.of(row));
        when(repository.findByClusterId(CLUSTER)).thenAnswer(invocation -> List.copyOf(stored));
        doAnswer(invocation -> {
                    stored.remove(invocation.getArgument(0));
                    return null;
                })
                .when(repository)
                .delete(any(KafkaReassignmentEntity.class));
        when(resources.read(any(ResourceQuery.class))).thenReturn(page(false));

        restore.restore(CLUSTER);
        restore.restore(CLUSTER);

        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(operations, times(2)).execute(any(), sent.capture(), eq(Scope.whole()), eq(Precondition.none()));
        assertThat(sent.getAllValues())
                .allSatisfy(operation -> assertThat(operation).isInstanceOf(Operation.Update.class));
        Operation.Update broker = (Operation.Update) sent.getAllValues().get(0);
        Operation.Update topic = (Operation.Update) sent.getAllValues().get(1);
        assertThat(broker.patch().asMap())
                .containsEntry(KafkaKinds.CONFIG_PREFIX + "leader.replication.throttled.rate", "5000")
                .containsEntry(KafkaKinds.CONFIG_PREFIX + "follower.replication.throttled.rate", "");
        assertThat(topic.patch().asMap())
                .containsEntry(KafkaKinds.CONFIG_PREFIX + "follower.replication.throttled.replicas", "");
        verify(repository).delete(row);
        verify(operations, times(2)).execute(any(), any(), any(), any());
    }

    @Test
    void aMoveStillListedIsLeftAlone() {
        when(repository.findByClusterId(CLUSTER)).thenReturn(List.of(row()));
        when(resources.read(any(ResourceQuery.class))).thenReturn(page(true));

        restore.restore(CLUSTER);

        verify(operations, never()).execute(any(), any(), any(), any());
        verify(repository, never()).delete(any());
    }

    private static KafkaReassignmentEntity row() {
        return new KafkaReassignmentEntity(
                CLUSTER,
                "orders",
                0,
                7L,
                Map.of("broker.2.leader.replication.throttled.rate", "5000"),
                "broker.2.follower.replication.throttled.rate,topic.follower.replication.throttled.replicas",
                Instant.EPOCH);
    }

    private static Partial<ResourcePage> page(boolean moving) {
        Attributes.Builder attributes = Attributes.builder();
        if (moving) {
            attributes.putRaw(KafkaKinds.REASSIGNMENT, "replicas=[1, 2, 3],adding=[3],removing=[2]");
        }
        Resource resource = new Resource(
                ResourceId.of("kafka.partition", "orders", "0"),
                attributes.build(),
                List.of(),
                List.of(),
                Instant.EPOCH);
        return Partial.complete(new ResourcePage(List.of(resource), Optional.empty()), List.of());
    }
}
