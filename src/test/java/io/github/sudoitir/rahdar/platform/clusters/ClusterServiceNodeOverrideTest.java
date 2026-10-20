package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Overriding a node's management URL: one URL names one node of a cluster. */
@ExtendWith(MockitoExtension.class)
class ClusterServiceNodeOverrideTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String TAKEN = "http://b:8161/console/jolokia";

    @Mock
    ClusterRepository clusters;

    @Mock
    BrokerNodeRepository nodes;

    @Mock
    AuditService audit;

    @Mock
    ClusterAccessGuard clusterAccess;

    @InjectMocks
    ClusterService service;

    @Test
    void aUrlAnotherNodeOfTheClusterHoldsIsRefusedBeforeAnythingIsAudited() {
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(CLUSTER, "a:61616", "PRIMARY", null);
        UUID nodeId = UUID.randomUUID();
        ReflectionTestUtils.setField(node, "id", nodeId);
        when(clusters.findById(CLUSTER)).thenReturn(Optional.of(mock(ClusterEntity.class)));
        when(nodes.findById(nodeId)).thenReturn(Optional.of(node));
        when(nodes.existsManagementUrlElsewhere(CLUSTER, TAKEN, nodeId)).thenReturn(true);

        NodeOverrideRequest request = new NodeOverrideRequest(TAKEN, null);
        assertThatThrownBy(() -> service.overrideNodeUrl(CLUSTER, nodeId, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(TAKEN);

        verifyNoInteractions(audit);
        verify(nodes, never()).save(any());
    }
}
