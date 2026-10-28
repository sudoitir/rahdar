package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaAclChanges.AclChangeParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclPreview;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** An ACL change records what the cluster holds afterwards, and what was applied when it stops part way. */
class KafkaAclChangesTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Acl A = new Acl("User:a", "*", "read", "allow", "topic", "literal", "t1");
    private static final Acl B = new Acl("User:b", "*", "read", "allow", "topic", "literal", "t2");
    private static final Acl OLD = new Acl("User:old", "*", "read", "allow", "topic", "literal", "t3");

    private final KafkaAcls acls = mock(KafkaAcls.class);
    private final Operations operations = mock(Operations.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaAclChanges changes;

    KafkaAclChangesTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        when(acls.preview(any(), any())).thenReturn(new AclPreview(List.of(), List.of(), List.of(), 2, 1, "etag"));
        changes = new KafkaAclChanges(acls, gateway, mock(OperationGate.class), audit, mock(ActorResolver.class));
    }

    private static AclChangeParams params() {
        return new AclChangeParams(CLUSTER, List.of(A, B), List.of(OLD), "etag");
    }

    @Test
    void theRowRecordsWhatTheClusterHoldsAfterTheCalls() {
        when(operations.execute(any(), any(), any()))
                .thenReturn(Partial.complete(mock(OperationResult.class), List.of()));
        when(acls.all(CLUSTER)).thenReturn(List.of(A));

        changes.replay(params());

        ArgumentCaptor<Object> detail = ArgumentCaptor.forClass(Object.class);
        verify(audit).finish(any(), eq(true), anyLong(), any(), detail.capture());
        assertThat(String.valueOf(detail.getValue())).contains("missing=[").contains("t2");
    }

    @Test
    void aFailureAfterSomeCallsRecordsWhatWasAppliedAndWhatWasNot() {
        when(operations.execute(any(), any(), any()))
                .thenReturn(Partial.complete(mock(OperationResult.class), List.of()))
                .thenThrow(new IllegalStateException("the broker went away"));

        assertThatThrownBy(() -> changes.replay(params())).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Object> detail = ArgumentCaptor.forClass(Object.class);
        verify(audit).finish(any(), eq(true), eq(1L), any(), detail.capture());
        String recorded = String.valueOf(detail.getValue());
        assertThat(recorded).contains("added=[Acl[principal=User:a");
        assertThat(recorded).contains("notAdded=[Acl[principal=User:b");
        assertThat(recorded).contains("notRemoved=[Acl[principal=User:old");
    }
}
