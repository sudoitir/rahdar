package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Queue delete against a real Artemis (ADR-0084): a consumer is refused until the operator
 * opts in, a divert that forwards into the queue's last address goes with it, and producers
 * still route afterwards. A divert out of that address keeps it bound, so then both stay and
 * the chain through it still delivers (ADR-0085).
 *
 * <p>Nothing seeds {@code subscription_snapshot} here, so the delete also proves a queue the scrape
 * has not reached is found on the live node.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class QueueDeleteRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    WebApplicationContext webContext;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String src = "QDEL." + run + ".SRC";
    private final String dst = "QDEL." + run + ".DST";
    private final String other = "QDEL." + run + ".OTHER";
    private final String feed = "feed-" + run;
    private final String fanOut = "fan-out-" + run;
    private UUID clusterId;
    private ArtemisNode client;
    private String broker;
    private ActiveMQConnectionFactory factory;

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "queue-delete-real-" + run,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
        factory = new ActiveMQConnectionFactory(
                ArtemisIntegrationTest.coreUrl() + "?useTopologyForLoadBalancing=false",
                ArtemisIntegrationTest.BROKER_USER,
                ArtemisIntegrationTest.BROKER_PASSWORD);

        queueOps.createAddress(client, broker, src, "MULTICAST");
        queueOps.createQueue(client, broker, queue(src + ".copy", src, "MULTICAST"));
        queueOps.createQueue(client, broker, queue(dst, dst, "ANYCAST"));
        queueOps.createQueue(client, broker, queue(other, other, "ANYCAST"));
        divertOps.createDivert(client, broker, DivertOperations.divertConfig(feed, null, src, dst, false, null, null));
    }

    private static Map<String, Object> queue(String name, String address, String routingType) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", name);
        config.put("address", address);
        config.put("routing-type", routingType);
        config.put("durable", true);
        config.put("auto-create-address", true);
        return config;
    }

    @AfterEach
    void cleanUp() {
        for (String divert : List.of(feed, fanOut)) {
            quietly(() -> divertOps.destroyDivert(client, broker, divert));
        }
        for (String queue : List.of(src + ".copy", dst, other)) {
            quietly(() -> queueOps.destroyQueue(client, broker, queue, true));
        }
        for (String address : List.of(src, dst, other)) {
            quietly(() -> queueOps.deleteAddress(client, broker, address));
        }
        factory.close();
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException _) {
            // already gone
        }
    }

    private NodeOutcome deleteDst(boolean dryRun, boolean disconnectConsumers) {
        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>)
                        lifecycle.deleteQueue(clusterId, dst, dryRun, false, disconnectConsumers))
                .value();
        assertThat(outcome.nodes()).hasSize(1);
        return outcome.nodes().get(0);
    }

    private boolean dstQueueExists() {
        return queueOps.boundQueues(client, MBeanNames.address(broker, dst)).contains(dst);
    }

    @Test
    void aConsumerIsRefusedUntilDisconnectIsAskedForAndTheDependentDivertGoesWithTheQueue() throws Exception {
        try (Connection consumerConnection = factory.createConnection()) {
            consumerConnection.start();
            Session session = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue(dst));

            NodeOutcome refused = deleteDst(true, false);
            assertThat(refused.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(refused.error()).contains("1 consumer").contains("disconnectConsumers");

            assertThat(deleteDst(false, false).status()).isEqualTo(NodeStatus.FAILED);
            assertThat(dstQueueExists()).isTrue();
            assertThat(divertOps.find(client, feed)).isPresent();

            // The same preview over REST, with the flag: the blast radius names the divert.
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
            mvc.perform(delete("/api/v1/clusters/" + clusterId + "/resources/artemis.queue/" + dst)
                            .param("dryRun", "true")
                            .param("disconnectConsumers", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nodes[0].status").value("WOULD_APPLY"))
                    .andExpect(jsonPath("$.nodes[0].error").value(Matchers.containsString(feed)));

            NodeOutcome deleted = deleteDst(false, true);
            assertThat(deleted.status()).isEqualTo(NodeStatus.APPLIED);
            assertThat(deleted.error()).contains(feed);
        }

        assertThat(dstQueueExists()).isFalse();
        assertThat(divertOps.find(client, feed)).isEmpty();

        // Producers to the divert's source still route, and nothing brings the queue back.
        try (Connection producerConnection = factory.createConnection()) {
            Session session = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createTopic(src)).send(session.createTextMessage("after"));
        }
        assertThat(queueOps.messageCount(client, MBeanNames.queue(broker, src, src + ".copy", "MULTICAST")))
                .isEqualTo(1L);
        assertThat(dstQueueExists()).isFalse();

        AuditEventEntity row = auditEvents.findAll().stream()
                .filter(e -> "DELETE_SUBSCRIPTION".equals(e.getAction())
                        && clusterId.equals(e.getClusterId())
                        && !e.isDryRun()
                        && "SUCCESS".equals(e.getOutcome()))
                .findFirst()
                .orElseThrow();
        assertThat(row.getParams()).contains("disconnectConsumers");
        assertThat(row.getOutcomeDetail()).contains(feed).contains(src);
    }

    @Test
    void aDivertIntoAnAddressADivertKeepsBoundStaysAndTheChainStillDelivers() throws Exception {
        divertOps.createDivert(
                client, broker, DivertOperations.divertConfig(fanOut, null, dst, other, false, null, null));

        NodeOutcome preview = deleteDst(true, false);
        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error()).contains(feed).contains(fanOut).contains("kept");

        assertThat(deleteDst(false, false).status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(dstQueueExists()).isFalse();
        assertThat(divertOps.find(client, feed)).isPresent();
        assertThat(divertOps.find(client, fanOut)).isPresent();

        // SRC → DST → OTHER still delivers: the delete did not cut the chain through DST.
        try (Connection producerConnection = factory.createConnection()) {
            Session session = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createTopic(src)).send(session.createTextMessage("through"));
        }
        assertThat(queueOps.messageCount(client, MBeanNames.queue(broker, other, other, "ANYCAST")))
                .isEqualTo(1L);
    }
}
