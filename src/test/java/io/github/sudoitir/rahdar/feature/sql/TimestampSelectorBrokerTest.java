package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The timestamp selector against a real broker, on both paths a query takes: a browse, and a count
 * the broker's management answers. A JMS client translates {@code JMSTimestamp} for its own browsers,
 * which is how the wrong identifier went unnoticed; neither path does, and both match nothing on it.
 */
class TimestampSelectorBrokerTest extends ArtemisIntegrationTest {

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private ClusterConnection connection;
    private BrokerMessages messages;
    private String queue;

    @BeforeEach
    void seed() throws Exception {
        queue = "selector.it." + System.nanoTime();
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection jms = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createQueue(queue)).send(session.createTextMessage("stamped"));
            session.close();
        } finally {
            factory.close();
        }
        connection = cluster(nodeId);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(clusterId)).thenReturn(connection);
        messages = new BrokerMessages(gateway);
    }

    @AfterEach
    void close() {
        connection.close();
    }

    private long browseCount(String selector) {
        return messages.browse(clusterId, nodeId, queue, 1, 50, selector)
                .messages()
                .size();
    }

    private long managementCount(String selector) {
        return messages.count(clusterId, nodeId, queue, selector);
    }

    @Test
    void theCatalogueTimestampIdentifierMatchesOnBothPaths() {
        String timestamp = ColumnCatalogue.Column.TIMESTAMP.selectorId();

        assertThat(browseCount(null)).isEqualTo(1);
        assertThat(browseCount(timestamp + " >= 0")).isEqualTo(1);
        assertThat(managementCount(timestamp + " >= 0")).isEqualTo(1);
        // The identifier the tail and the planner used to send: a silent empty result, not an error.
        assertThat(browseCount("JMSTimestamp >= 0")).isZero();
        assertThat(managementCount("JMSTimestamp >= 0")).isZero();
    }
}
