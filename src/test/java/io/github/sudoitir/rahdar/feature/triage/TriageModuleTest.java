package io.github.sudoitir.rahdar.feature.triage;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Triage starts with its direct dependencies only — the check behind ADR-0089's claim
 * that the consumer-health verdict needed no new module and opened no cycle.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class TriageModuleTest extends ModuleIntegrationTest {

    /** The scrape cycle, in a direct dependency, persists each node's split-brain verdict through the clusters module. */
    @MockitoBean
    NodeStateRecorder nodeStateRecorder;

    /** The scrape module's queue locator lists a cluster's nodes... */
    @MockitoBean
    ClusterDirectory clusterDirectory;

    /** ...and searches them for a queue the scrape has not reached. */
    @MockitoBean
    BrokerConnections brokerConnections;

    /**
     * The kernel's permission checks walk the scope hierarchy. Mocked as the clusters
     * module's implementation rather than as {@code ScopeHierarchy}: triage boots that
     * module, so mocking the interface would replace the real {@code clusterEnvironmentIndex}
     * bean that {@code ClusterService} injects by type.
     */
    @MockitoBean
    ClusterEnvironmentIndex clusterEnvironmentIndex;

    /** Broker events, a direct dependency, mask notification props through the governance policy. */
    @MockitoBean
    ContentPolicy contentPolicy;

    @MockitoBean
    BrokerCommands brokerCommands;

    @MockitoBean
    ArtemisNodes artemisNodes;

    /** Queue lifecycle, in a direct dependency, resets counters through the system module, which is not started. */
    @MockitoBean
    QueueCounters queueCounters;
}
