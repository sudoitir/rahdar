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
    private final CorePool pool = mock(CorePool.class);
    private final CoreRelay relay = mock(CoreRelay.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final BrokerSessions sessions = new BrokerSessions(subscriptions, pool, relay, gateway);

    @Test
    void aClusterDeletedOnAnyReplicaIsReleasedHere() {
        UUID clusterId = UUID.randomUUID();

        sessions.onClusterDeleted(new ReplicaSignal("cluster-deleted", clusterId.toString()));

        verify(subscriptions).forget(clusterId);
        verify(pool).forget(clusterId);
        verify(relay).forget(clusterId);
        verify(gateway).forget(clusterId);
    }

    @Test
    void afterTheBusWasDownOnlyWhatBelongsToAClusterThatIsGoneIsReleased() {
        UUID kept = UUID.randomUUID();
        UUID goneWithSubscriptions = UUID.randomUUID();
        UUID goneWithAPool = UUID.randomUUID();
        UUID goneWithAConnection = UUID.randomUUID();
        when(subscriptions.clusterIds()).thenReturn(Set.of(kept, goneWithSubscriptions));
        when(pool.clusterIds()).thenReturn(Set.of(kept, goneWithAPool));
        when(relay.clusterIds()).thenReturn(Set.of());
        when(gateway.clusterIds()).thenReturn(Set.of(kept, goneWithAConnection));

        sessions.releaseAllBut(Set.of(kept));

        verify(subscriptions).forget(goneWithSubscriptions);
        verify(pool).forget(goneWithSubscriptions);
        verify(subscriptions).forget(goneWithAPool);
        verify(pool).forget(goneWithAPool);
        verify(gateway).forget(goneWithAConnection);
        verify(subscriptions, never()).forget(kept);
        verify(pool, never()).forget(kept);
        verify(relay, never()).forget(kept);
        verify(gateway, never()).forget(kept);
    }
}
