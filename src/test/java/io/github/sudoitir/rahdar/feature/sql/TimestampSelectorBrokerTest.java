package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.MessageBrowser;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The timestamp selector against a real broker's management browse, the path every Jolokia query and every index
 * tail takes. A JMS client translates {@code JMSTimestamp} for its own browsers, which is how the wrong identifier
 * went unnoticed; the management operation does no translation and matches nothing on it.
 */
class TimestampSelectorBrokerTest extends ArtemisIntegrationTest {

    private final MessageBrowser browser = new MessageBrowser();
    private ArtemisNode client;
    private String queue;

    @BeforeEach
    void seed() throws Exception {
        queue = "selector.it." + System.nanoTime();
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection connection = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createQueue(queue)).send(session.createTextMessage("stamped"));
            session.close();
        } finally {
            factory.close();
        }
        client = jolokiaClient();
    }

    private long browseCount(String selector) {
        String mbean = ArtemisMBeans.queue(client.resolveBrokerObjectName(), queue, queue, "ANYCAST");
        return browser.browse(client, mbean, 1, 50, selector).messages().size();
    }

    @Test
    void theCatalogueTimestampIdentifierMatchesOnTheManagementBrowse() {
        String timestamp = ColumnCatalogue.Column.TIMESTAMP.selectorId();

        assertThat(browseCount(null)).isEqualTo(1);
        assertThat(browseCount(timestamp + " >= 0")).isEqualTo(1);
        // The identifier the tail and the planner used to send: a silent empty result, not an error.
        assertThat(browseCount("JMSTimestamp >= 0")).isZero();
    }
}
