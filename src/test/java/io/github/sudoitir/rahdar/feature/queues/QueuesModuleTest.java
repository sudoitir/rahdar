package io.github.sudoitir.rahdar.feature.queues;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.BrokerSessions;
import io.github.sudoitir.rahdar.platform.broker.DataPlaneCheck;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Queues starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class QueuesModuleTest extends ModuleIntegrationTest {

    // The clusters module, a direct dependency, registers clusters and runs broker commands over
    // the broker module, which queues itself does not use.

    @MockitoBean
    ArtemisNodes clientFactory;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerSessions sessions;

    @MockitoBean
    DataPlaneCheck dataPlaneCheck;

    @MockitoBean
    BrokerNotifications coreSubscriptions;

    @MockitoBean
    NodeCallLimiter limiter;
}
