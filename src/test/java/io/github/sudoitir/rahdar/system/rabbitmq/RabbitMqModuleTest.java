package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.NodeDirectory;
import io.github.sudoitir.rahdar.platform.broker.ReplicaDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The RabbitMQ module starts with its direct dependencies only, and its schema validates. */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class RabbitMqModuleTest extends ModuleIntegrationTest {

    /** The kernel's permission checks walk the scope hierarchy the clusters module implements. */
    @MockitoBean
    ClusterEnvironmentIndex scopeHierarchy;

    /** The broker platform reads the nodes of a cluster from the clusters module, which is not started here. */
    @MockitoBean
    NodeDirectory nodeDirectory;

    @MockitoBean
    ConnectionSettingsSource connectionSettings;

    /** The scrape reads and records the clusters module's nodes; the clusters module is not started here. */
    @MockitoBean
    ClusterDirectory clusterDirectory;

    @MockitoBean
    NodeStateRecorder nodeStateRecorder;

    @MockitoBean
    ReplicaDirectory replicaDirectory;
}
