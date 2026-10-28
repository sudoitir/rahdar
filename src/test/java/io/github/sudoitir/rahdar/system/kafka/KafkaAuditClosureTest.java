package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges.ConfigChangeParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactionAborts.AbortParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An audit row begun before a broker call is finished whatever the call throws, so none is left open. */
class KafkaAuditClosureTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final AuditService audit = mock(AuditService.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);

    KafkaAuditClosureTest() {
        ClusterConnection connection = mock(ClusterConnection.class);
        Operations operations = mock(Operations.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(operations.execute(any(), any(), any())).thenThrow(new IllegalStateException("the broker failed"));
        when(operations.execute(any(), any(), any(), any())).thenThrow(new IllegalStateException("the broker failed"));
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
    }

    @Test
    void aTopicConfigChangeThatFailsClosesItsRow() {
        KafkaTopicConfigs configs = mock(KafkaTopicConfigs.class);
        when(configs.preview(any(), any(), any()))
                .thenReturn(new ConfigPreview(
                        List.of(new ConfigChangeRow("retention.ms", "1", "2", null, null)), true, "etag"));
        KafkaTopicConfigChanges changes = new KafkaTopicConfigChanges(
                configs, gateway, mock(OperationGate.class), audit, mock(ActorResolver.class));

        assertThatThrownBy(() -> changes.replay(
                        new ConfigChangeParams(CLUSTER, "orders", Map.of("retention.ms", "2"), null, "etag")))
                .isInstanceOf(IllegalStateException.class);

        verify(audit).fail(any(), any());
    }

    @Test
    void anAbortThatFailsClosesItsRow() {
        KafkaTransactions transactions = mock(KafkaTransactions.class);
        when(transactions.abortPreview(CLUSTER, "tx"))
                .thenReturn(new AbortPreview("tx", "ongoing", List.of(), "aborts it", true, "etag"));
        KafkaTransactionAborts aborts = new KafkaTransactionAborts(
                transactions, gateway, mock(OperationGate.class), audit, mock(ActorResolver.class));

        assertThatThrownBy(() -> aborts.replay(new AbortParams(CLUSTER, "tx", "etag")))
                .isInstanceOf(IllegalStateException.class);

        verify(audit).fail(any(), any());
    }
}
