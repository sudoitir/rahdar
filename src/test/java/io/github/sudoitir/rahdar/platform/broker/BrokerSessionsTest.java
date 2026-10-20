package io.github.sudoitir.rahdar.platform.broker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrokerSessionsTest {

    private final BrokerNotifications subscriptions = mock(BrokerNotifications.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final BrokerSessions sessions = new BrokerSessions(subscriptions, gateway);

    @Test
    void aClusterDeletedOnAnyReplicaIsReleasedHere() {
        UUID clusterId = UUID.randomUUID();

        sessions.onClusterDeleted(new ReplicaSignal("cluster-deleted", clusterId.toString()));

        verify(subscriptions).forget(clusterId);
        verify(gateway).forget(clusterId);
    }

    @Test
    void afterTheBusWasDownOnlyWhatBelongsToAClusterThatIsGoneIsReleased() {
        UUID kept = UUID.randomUUID();
        UUID goneWithSubscriptions = UUID.randomUUID();
        UUID goneWithAConnection = UUID.randomUUID();
        when(subscriptions.clusterIds()).thenReturn(Set.of(kept, goneWithSubscriptions));
        when(gateway.clusterIds()).thenReturn(Set.of(kept, goneWithAConnection));

        sessions.releaseAllBut(Set.of(kept));

        verify(subscriptions).forget(goneWithSubscriptions);
        verify(gateway).forget(goneWithAConnection);
        verify(subscriptions, never()).forget(kept);
        verify(gateway, never()).forget(kept);
    }
}
