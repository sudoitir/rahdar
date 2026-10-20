package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.QueueLocator.QueueLocation;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

/**
 * The live lookup against a real broker, with no snapshot to answer first. The pattern read's
 * response shape is the broker's, not an assumption: a shape the locator misread would skip the
 * node silently and report a queue on the broker as one that does not exist.
 */
class QueueLocatorBrokerTest extends ArtemisIntegrationTest {

    @Test
    void findsAQueueTheScrapeHasNotReachedWithItsAddressRoutingTypeAndDepth() throws Exception {
        UUID clusterId = UUID.randomUUID();
        String address = "locator.it." + System.nanoTime();
        String queueName = address + ".sub";

        ArtemisNode client = jolokiaClient();
        client.execOnBroker(
                "createQueue(java.lang.String,boolean)",
                "{\"name\":\"" + queueName + "\",\"address\":\"" + address
                        + "\",\"routing-type\":\"MULTICAST\",\"durable\":true}",
                false);
        seed(address, 3);

        ClusterNode node = mock(ClusterNode.class);
        UUID nodeId = UUID.randomUUID();
        when(node.getId()).thenReturn(nodeId);
        when(node.getJolokiaUrl()).thenReturn(jolokiaUrl());
        when(node.getActive()).thenReturn(true);
        ClusterDirectory clusters = mock(ClusterDirectory.class);
        when(clusters.nodes(clusterId)).thenReturn(List.of(node));
        BrokerConnections connections = mock(BrokerConnections.class);
        ArtemisNodes artemisNodes = mock(ArtemisNodes.class);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(client);
        QueueSnapshots snapshots = mock(QueueSnapshots.class);
        when(snapshots.forCluster(clusterId)).thenReturn(List.of());

        List<QueueLocation> found =
                new QueueLocator(snapshots, clusters, connections, artemisNodes).locate(clusterId, queueName);

        assertThat(found).containsExactly(new QueueLocation(nodeId, queueName, address, "MULTICAST", 3));
    }

    private static void seed(String address, int messages) throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var producer = session.createProducer(session.createTopic(address));
            for (int i = 0; i < messages; i++) {
                producer.send(session.createTextMessage("m" + i));
            }
        } finally {
            factory.close();
        }
    }
}
