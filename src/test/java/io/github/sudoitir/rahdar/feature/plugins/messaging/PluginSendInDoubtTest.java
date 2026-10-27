package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeRepository;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationRepository;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.AccessCheck;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginDrains;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.SendInDoubtException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** A plugin's send whose confirmation never came back is in doubt, for the plugin and the audit (ADR-0211). */
class PluginSendInDoubtTest {

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final AccessCheck access = mock(AccessCheck.class);
    private final PluginMessagingReconciler reconciler = mock(PluginMessagingReconciler.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final BrokerMessages messages = mock(BrokerMessages.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final AuditEvent event = mock(AuditEvent.class);
    private final PluginMessagingService service = new PluginMessagingService(
            mock(RegistrationRepository.class),
            mock(RegistrationNodeRepository.class),
            access,
            reconciler,
            mock(PluginDrains.class),
            clusters,
            messages,
            audit,
            actors,
            mock(TransactionTemplate.class),
            Clock.systemUTC());

    @Test
    void anUnconfirmedSendIsInDoubtForThePluginAndTheAudit() {
        UUID user = UUID.randomUUID();
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(nodeId);
        when(node.getDataUrl()).thenReturn("tcp://node:61616");
        when(clusters.cluster(clusterId)).thenReturn(Optional.of(mock(RegisteredCluster.class)));
        when(access.denial(any(), any(), any())).thenReturn(Optional.empty());
        when(reconciler.servingNodes(clusterId)).thenReturn(List.of(node));
        when(actors.resolve()).thenReturn(new Actor("ann", null, null, user, null));
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(event);
        when(messages.sendTo(any(), any(), any(), any())).thenThrow(new SendInDoubtException("no confirmation", null));
        OutboundMessage message = new OutboundMessage(
                clusterId, ResourceRef.destination("orders"), "x".getBytes(), true, Map.of(), Map.of(), true, user);

        assertThatThrownBy(() -> service.send("acme-x", message))
                .isInstanceOf(MessageInDoubtException.class)
                .hasMessage("no confirmation");
        verify(audit).inDoubt(event, "no confirmation");
        verify(audit, never()).succeed(any(), anyLong());
        verify(audit, never()).fail(any(), anyString());
    }
}
