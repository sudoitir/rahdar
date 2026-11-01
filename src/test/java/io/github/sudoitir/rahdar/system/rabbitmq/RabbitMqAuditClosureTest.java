package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.ETAG;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.preview;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicySet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An audit row begun before a broker call is finished whatever the call throws, so none is left open. */
class RabbitMqAuditClosureTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String VHOST =
            ResourceId.of(RabbitMqIds.VHOST, "orders").format();

    private final AuditService audit = mock(AuditService.class);
    private final RabbitMqOps ops = mock(RabbitMqOps.class);
    private final RabbitMqChanges changes =
            new RabbitMqChanges(ops, mock(OperationGate.class), audit, mock(ActorResolver.class));

    RabbitMqAuditClosureTest() {
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
    }

    @Test
    void aPreviewThatFailsClosesTheRow() {
        when(ops.preview(eq(CLUSTER), any(), any(), any())).thenThrow(new IllegalStateException("the broker failed"));

        assertThatThrownBy(() -> changes.replay(new NamespaceDelete(CLUSTER, VHOST, Map.of(), ETAG)))
                .isInstanceOf(IllegalStateException.class);

        verify(audit).fail(any(), any());
        verify(audit, never()).finish(any(), anyBoolean(), anyLong(), any(), any());
    }

    @Test
    void aWriteThatFailsClosesTheRow() {
        when(ops.preview(eq(CLUSTER), any(), any(), any())).thenReturn(preview(ETAG, 3));
        when(ops.execute(eq(CLUSTER), any(), any(), any(), any())).thenThrow(new IllegalStateException("went away"));

        assertThatThrownBy(() -> changes.replay(new NamespaceDelete(CLUSTER, VHOST, Map.of(), ETAG)))
                .isInstanceOf(IllegalStateException.class);

        verify(audit).fail(any(), any());
    }

    @Test
    void aChangeWhoseEtagMovedIsRefusedAsStaleAndClosesTheRow() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.CREATE), any())).thenReturn(preview("etag-2", 0));

        assertThatThrownBy(() -> changes.replay(new PolicySet(
                        CLUSTER, ResourceId.of(RabbitMqIds.POLICY, "/", "ha").format(), Map.of(), ETAG)))
                .isInstanceOf(StalePreviewException.class);

        verify(audit).fail(any(), any());
        verify(ops, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void aCreateThatFailsClosesTheRow() {
        when(ops.execute(eq(CLUSTER), any(), any(), any(), any())).thenThrow(new IllegalStateException("went away"));
        ResourceId queue = ResourceId.of(RabbitMqIds.CLASSIC_QUEUE, "/", "q");

        assertThatThrownBy(() -> changes.create(CLUSTER, queue, Map.of(), null, "CREATE_SUBSCRIPTION", "SUBSCRIPTION"))
                .isInstanceOf(IllegalStateException.class);

        verify(audit).fail(any(), any());
    }
}
