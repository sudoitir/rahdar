package io.github.sudoitir.rahdar.platform.clusters;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.replica.BusResumed;
import io.github.sudoitir.rahdar.platform.broker.BrokerSessions;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BrokerSessionSweepTest {

    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final BrokerSessions sessions = mock(BrokerSessions.class);
    private final BrokerSessionSweep sweep = new BrokerSessionSweep(clusters, sessions);

    @Test
    void theBusComingBackReleasesWhatIsHeldForClustersNoLongerInTheDatabase() {
        UUID existing = UUID.randomUUID();
        ClusterEntity cluster = new ClusterEntity(ArtemisModule.SYSTEM, "c", null, null);
        ReflectionTestUtils.setField(cluster, "id", existing);
        when(clusters.findAll()).thenReturn(List.of(cluster));

        sweep.on(new BusResumed());

        verify(sessions).releaseAllBut(Set.of(existing));
    }
}
