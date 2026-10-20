package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/** A command opens its audit row before it checks access, so a refused one leaves a {@code REFUSED} event. */
class BrokerCommandsRefusalTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterDirectory nodes = mock(ClusterDirectory.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final AuditService audit = mock(AuditService.class);
    private final ClusterAccessGuard guard = mock(ClusterAccessGuard.class);
    private final AuditEvent event = mock(AuditEvent.class);
    private final BrokerCommands commands = new BrokerCommands(
            nodes,
            connections,
            audit,
            mock(ActorResolver.class),
            mock(SettingsService.class),
            guard,
            mock(CapabilityLedger.class),
            gateway);

    private Command command() {
        return Command.builder()
                .clusterId(CLUSTER)
                .permission("queue:purge")
                .resources(List.of(ResourceRef.queue("orders.in")))
                .auditAction("PURGE_QUEUE")
                .targetType("QUEUE")
                .targetName("orders.in")
                .action(node -> NodeStatus.APPLIED)
                .build();
    }

    @Test
    void aForbiddenCommandIsAuditedRefusedAndNeverReachesABroker() {
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(event);
        doThrow(new AccessDeniedException("no queue:purge")).when(guard).requireAll(eq(CLUSTER), any());

        var command = command();
        assertThatThrownBy(() -> commands.run(command)).isInstanceOf(AccessDeniedException.class);

        InOrder order = inOrder(audit, guard);
        order.verify(audit)
                .begin(any(), eq("PURGE_QUEUE"), eq("QUEUE"), eq("orders.in"), any(), any(), any(), eq(false));
        order.verify(guard).requireAll(eq(CLUSTER), any());
        order.verify(audit).refuse(event, "no queue:purge");
        verify(audit, never()).fail(any(), any());
        verify(gateway, never()).node(any(UUID.class), any(UUID.class));
    }

    @Test
    void aHiddenResourceIsAuditedRefusedToo() {
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(event);
        doThrow(new NotFoundException("queue", "orders.in")).when(guard).requireAll(eq(CLUSTER), any());

        var command = command();
        assertThatThrownBy(() -> commands.run(command)).isInstanceOf(NotFoundException.class);

        verify(audit).refuse(eq(event), any());
    }
}
