package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsets.Plan;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetResult;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A reset of several topics that stops part way says which topics moved and which did not, and audits both. */
class KafkaOffsetResetsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final KafkaOffsets offsets = mock(KafkaOffsets.class);
    private final Operations operations = mock(Operations.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaOffsetResets resets;

    @SuppressWarnings("unchecked")
    KafkaOffsetResetsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        OperationGate gate = mock(OperationGate.class);
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        resets = new KafkaOffsetResets(offsets, gateway, gate, audit, mock(ActorResolver.class));
        Map<String, ResourceId> ids = new LinkedHashMap<>();
        Map<String, String> etags = new LinkedHashMap<>();
        Map<String, Position> positions = new LinkedHashMap<>();
        for (String topic : List.of("a", "b", "c")) {
            ids.put(topic, ResourceId.of("kafka.group-topic", "g", topic));
            etags.put(topic, "e-" + topic);
            positions.put(topic, new Position.AtTime(Instant.EPOCH));
        }
        List<OffsetRow> rows = List.of(
                new OffsetRow("a", 0, 5L, 0L, 0, 5, null, null),
                new OffsetRow("b", 0, 5L, 0L, 0, 5, null, null),
                new OffsetRow("b", 1, 7L, 0L, 0, 7, null, null),
                new OffsetRow("c", 0, 9L, 0L, 0, 9, null, null));
        when(offsets.plan(any(), any(), any()))
                .thenReturn(new Plan(new OffsetPreview(rows, List.of(), null, "etag"), ids, etags, positions));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailureAfterSomeTopicsMovedIsAPartialOutcomeNamingBothAndAuditedAsFailed() {
        OperationResult done = mock(OperationResult.class);
        when(operations.execute(any(), any(), any(), any()))
                .thenReturn(Partial.complete(done, List.of()))
                .thenThrow(new IllegalStateException("the broker went away"));

        OffsetResult result = resets.reset(CLUSTER, "g", new OffsetRequest(null, "earliest", null, "etag"));

        assertThat(result.movedTopics()).containsExactly("a");
        assertThat(result.notMovedTopics()).containsExactly("b", "c");
        assertThat(result.failure()).contains("the broker went away");
        assertThat(result.moved()).isEqualTo(1);
        ArgumentCaptor<Object> detail = ArgumentCaptor.forClass(Object.class);
        verify(audit).finish(any(), eq(true), eq(1L), any(), detail.capture());
        assertThat(String.valueOf(detail.getValue()))
                .contains("movedTopics=[a]")
                .contains("notMovedTopics=[b, c]");
    }

    @Test
    void aFailureOnTheFirstTopicMovedNothingAndIsRefusedAsBefore() {
        when(operations.execute(any(), any(), any(), any())).thenThrow(new IllegalStateException("refused"));

        assertThatThrownBy(() -> resets.reset(CLUSTER, "g", new OffsetRequest(null, "earliest", null, "etag")))
                .isInstanceOf(IllegalStateException.class);

        verify(audit).fail(any(), any());
    }
}
