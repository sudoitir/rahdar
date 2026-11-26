package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingView;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaSchemaBindingEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaSchemaBindingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A binding names a serde this installation runs. A class name, a bad side or a stale etag stores nothing. */
class KafkaSchemaBindingsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final KafkaSchemaBindingRepository repository = mock(KafkaSchemaBindingRepository.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaSchemaBindings bindings;

    KafkaSchemaBindingsTest() {
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolve()).thenReturn(mock(Actor.class));
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        Mockito.doAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                })
                .when(gate)
                .run(any(), any());
        bindings = new KafkaSchemaBindings(repository, access, gate, audit, actors);
    }

    @Test
    void aReaderListsTheBindingsStoredForTheCluster() {
        when(repository.findByClusterIdOrderByTopicAscSideAsc(CLUSTER))
                .thenReturn(List.of(new KafkaSchemaBindingEntity(CLUSTER, "orders", "value", "avro")));

        assertThat(bindings.list(CLUSTER)).containsExactly(new BindingView("orders", "value", "avro"));
    }

    @Test
    void aReadIsRefusedWithoutSchemaRead() {
        Mockito.doThrow(new ResourceForbiddenException("schema:read", "cluster", CLUSTER.toString()))
                .when(access)
                .requireCluster(CLUSTER, KafkaModule.SCHEMA_READ);

        assertThatThrownBy(() -> bindings.list(CLUSTER))
                .isInstanceOf(ResourceForbiddenException.class)
                .hasMessageContaining("schema:read");
        verify(repository, never()).findByClusterIdOrderByTopicAscSideAsc(any());
    }

    @Test
    void aNewBindingIsStoredAndAReplayStoresItAgain() {
        when(repository.findByClusterIdAndTopicAndSide(CLUSTER, "orders", "value"))
                .thenReturn(Optional.empty());
        String etag = bindings.preview(CLUSTER, "orders", "value", "avro").etag();

        BindingView applied = bindings.apply(CLUSTER, "orders", "value", "avro", etag);
        bindings.replay(new BindingParams(CLUSTER, "orders", "value", "avro", etag));

        assertThat(applied).isEqualTo(new BindingView("orders", "value", "avro"));
        verify(repository, Mockito.times(2)).save(any());
        verify(audit, Mockito.times(2)).finish(any(), anyBoolean(), eq(1L), any(), any());
    }

    @Test
    void anExistingBindingChangesSerdeAndDoesNotInsertASecondRow() {
        KafkaSchemaBindingEntity row = new KafkaSchemaBindingEntity(CLUSTER, "orders", "key", "json");
        when(repository.findByClusterIdAndTopicAndSide(CLUSTER, "orders", "key"))
                .thenReturn(Optional.of(row));
        String etag = bindings.preview(CLUSTER, "orders", "key", "avro").etag();

        BindingView applied = bindings.apply(CLUSTER, "orders", "key", "avro", etag);

        assertThat(applied.serde()).isEqualTo("avro");
        assertThat(row.getSerde()).isEqualTo("avro");
        assertThat(bindings.serde(CLUSTER, "orders", "key")).isEqualTo("avro");
        verify(repository, never()).save(any());
    }

    @Test
    void aClassNameABadSideOrABlankTopicStoresNothing() {
        assertThatThrownBy(() -> bindings.preview(CLUSTER, "orders", "value", "com.example.Serde"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a class name");
        assertThatThrownBy(() -> bindings.preview(CLUSTER, "orders", "value", "string"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avro, protobuf, json");
        assertThatThrownBy(() -> bindings.apply(CLUSTER, "orders", "header", "avro", "etag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key or value");
        assertThatThrownBy(() -> bindings.preview(CLUSTER, " ", "value", "avro"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic is required");
        verify(repository, never()).save(any());
        verify(audit, never()).begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aStaleEtagWritesNothing() {
        when(repository.findByClusterIdAndTopicAndSide(CLUSTER, "orders", "value"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> bindings.apply(CLUSTER, "orders", "value", "avro", "stale"))
                .isInstanceOf(StalePreviewException.class);
        verify(repository, never()).save(any());
        verify(audit, never()).begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void anEstimateCountsOneBindingAndAMissingBindingDecodesAsUnset() {
        when(repository.findByClusterIdAndTopicAndSide(CLUSTER, "orders", "value"))
                .thenReturn(Optional.of(new KafkaSchemaBindingEntity(CLUSTER, "orders", "value", "json")));
        when(repository.findByClusterIdAndTopicAndSide(CLUSTER, "orders", "key"))
                .thenReturn(Optional.empty());

        Effect effect = bindings.estimate(new BindingParams(CLUSTER, "orders", "value", "avro", null));

        assertThat(effect.count()).isEqualTo(1);
        assertThat(effect.unit()).isEqualTo("binding");
        assertThat(effect.etag())
                .isEqualTo(bindings.preview(CLUSTER, "orders", "value", "avro").etag());
        assertThat(bindings.serde(CLUSTER, "orders", "key")).isNull();
    }
}
