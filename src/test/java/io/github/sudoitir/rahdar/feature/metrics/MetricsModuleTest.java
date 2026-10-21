package io.github.sudoitir.rahdar.feature.metrics;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.BrokerSessions;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.broker.DataPlaneCheck;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Metrics starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class MetricsModuleTest extends ModuleIntegrationTest {
    /** The scrape cycle, in a direct dependency, persists each node's split-brain verdict through the clusters module. */
    @MockitoBean
    NodeStateRecorder nodeStateRecorder;

    /** The scrape module's queue locator, in a direct dependency, lists a cluster's nodes... */
    @MockitoBean
    ClusterDirectory clusterDirectory;

    /** ...and searches them for a queue the scrape has not reached. */
    @MockitoBean
    BrokerConnections brokerConnections;

    /** The clusters module, a direct dependency since a split names its nodes (ADR-0110), builds broker clients. */
    @MockitoBean
    ArtemisNodes clientFactory;

    @MockitoBean
    BrokerSessions sessions;

    @MockitoBean
    DataPlaneCheck dataPlaneCheck;

    @MockitoBean
    BrokerNotifications coreSubscriptions;

    @MockitoBean
    NodeCallLimiter limiter;

    @MockitoBean
    BrokerGateway gateway;

    /** The clusters module, a direct dependency, names each cluster's kinds. */
    @MockitoBean
    ClusterKinds clusterKinds;

    /** The MCP module, a direct dependency, names the systems the broker module knows. */
    @MockitoBean
    BrokerSystems brokerSystems;
}
