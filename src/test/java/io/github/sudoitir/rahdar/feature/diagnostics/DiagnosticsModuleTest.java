package io.github.sudoitir.rahdar.feature.diagnostics;

import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilityProbe;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSessions;
import io.github.sudoitir.rahdar.platform.broker.CoreAccountCheck;
import io.github.sudoitir.rahdar.platform.broker.CoreSubscriptionCheck;
import io.github.sudoitir.rahdar.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Diagnostics starts with its direct dependencies only (ADR-0069). The clusters module, which it reads for the
 * cluster list, reaches brokers through the broker module; that is not bootstrapped, so its beans are mocked.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class DiagnosticsModuleTest extends ModuleIntegrationTest {

    @MockitoBean
    BrokerConnections brokerConnections;

    @MockitoBean
    ArtemisNodes clientFactory;

    @MockitoBean
    BrokerSessions sessions;

    @MockitoBean
    BrokerCapabilityProbe capabilityProbe;

    @MockitoBean
    CoreAccountCheck coreAccountCheck;

    @MockitoBean
    CoreSubscriptionCheck coreSubscriptionCheck;

    @MockitoBean
    CoreSubscriptionManager coreSubscriptions;

    @MockitoBean
    NodeCallLimiter limiter;

    @MockitoBean
    BrokerGateway gateway;
}
