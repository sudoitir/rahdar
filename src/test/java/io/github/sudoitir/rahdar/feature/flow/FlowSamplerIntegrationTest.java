package io.github.sudoitir.rahdar.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.StoredEdge;
import io.github.sudoitir.rahdar.kernel.jobs.ScheduledJob;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link ClientSampler} against a real Artemis 2.44 broker (task 3.10): the listing fields the
 * sampler reads exist, two sweeps give producer and consumer rates, and a row cap below the
 * client count is reported as truncation rather than hidden.
 */
class FlowSamplerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    ClientSampler sampler;

    @Autowired
    FlowStore store;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    /** The real client for the container, under the credentials a registered cluster would carry. */
    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    /**
     * The scheduled sweep, stopped. It forgets the rate baseline of every cluster nobody is watching,
     * and this test's cluster is watched by no one, so a scheduled run between the test's two sweeps
     * left the second one with no rate to report.
     */
    @TestBean(name = "flowSampleJob")
    ScheduledJob flowSampleJob;

    static ScheduledJob flowSampleJob() {
        return ScheduledJob.fixedDelay(
                "flow-sample", "flow", ScheduledJob.Scope.INSTANCE, () -> Duration.ofDays(1), () -> {});
    }

    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void register() {
        String jolokia = ArtemisIntegrationTest.jolokiaUrl();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "flow-it-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "artemis", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl(jolokia);
        nodeId = nodes.save(node).getId();
        when(artemisNodes.on(eq(clusterId), any(UUID.class)))
                .thenAnswer(inv -> ArtemisIntegrationTest.jolokiaClient(jolokia));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    @Test
    void twoSweepsOfARealBrokerGiveProducerAndConsumerRates() throws Exception {
        String queue = "flow.it." + System.nanoTime();
        var factory = new ActiveMQConnectionFactory(
                ArtemisIntegrationTest.coreUrl() + "?useTopologyForLoadBalancing=false",
                ArtemisIntegrationTest.BROKER_USER,
                ArtemisIntegrationTest.BROKER_PASSWORD);
        try (Connection connection = factory.createConnection()) {
            connection.setClientID("flow-it-app");
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));

            sampler.sweep(clusterId);
            // A rate is a delta over the time between two sweeps, so each attempt sends, sweeps again and looks.
            await("both edges of the queue are rated")
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        for (int i = 0; i < 50; i++) {
                            producer.send(session.createTextMessage("m" + i));
                            assertThat(consumer.receive(2_000)).isNotNull();
                        }
                        sampler.sweep(clusterId);

                        List<StoredEdge> edges = store.edges(clusterId).stream()
                                .filter(e -> queue.equals(e.edge().destination()))
                                .toList();
                        assertThat(edges)
                                .extracting(e -> e.edge().kind())
                                .containsExactlyInAnyOrder(Kind.PRODUCE, Kind.CONSUME);
                        assertThat(edges).allSatisfy(e -> {
                            assertThat(e.edge().clientId()).isEqualTo("flow-it-app");
                            assertThat(e.edge().rate()).isNotNull().isPositive();
                            assertThat(e.edge().memberCount()).isEqualTo(1);
                        });
                    });
            assertThat(store.nodeSamples(clusterId))
                    .singleElement()
                    .satisfies(s -> assertThat(s.errorKind()).isNull());
        }
    }

    @Test
    void routingIsSampledInTheSameSweepFromARealBroker() {
        String suffix = Long.toString(System.nanoTime());
        String source = "flow.it.src." + suffix;
        String target = "flow.it.dst." + suffix;
        String filtered = "flow.it.red." + suffix;
        ArtemisNode client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        assertThat(client.execOnBroker(
                                "createQueue(java.lang.String,java.lang.String,java.lang.String,boolean,java.lang.String)",
                                source,
                                filtered,
                                "color='red'",
                                true,
                                "ANYCAST")
                        .ok())
                .isTrue();
        String broker = client.resolveBrokerObjectName();
        assertThat(client.execOnBroker("createAddress(java.lang.String,java.lang.String)", target, "ANYCAST")
                        .ok())
                .isTrue();
        // Rahdar's own divert path: the JSON overload, which carries no null argument.
        divertOps.createDivert(
                client,
                broker,
                DivertOperations.divertConfig(
                        "flow-it-" + suffix, "flow-it-" + suffix, source, target, true, null, "ANYCAST"));

        sampler.sweep(clusterId);

        List<FlowStore.Route> routes = store.routes(clusterId).stream()
                .map(FlowStore.StoredRoute::route)
                .toList();
        assertThat(routes)
                .anySatisfy(r -> {
                    assertThat(r.kind()).isEqualTo(FlowStore.RouteKind.DIVERT);
                    assertThat(r.source()).isEqualTo(source);
                    assertThat(r.target()).isEqualTo(target);
                    assertThat(r.exclusive()).isTrue();
                })
                .anySatisfy(r -> {
                    assertThat(r.kind()).isEqualTo(FlowStore.RouteKind.QUEUE_FILTER);
                    assertThat(r.target()).isEqualTo(filtered);
                    assertThat(r.filter()).isEqualTo("color='red'");
                })
                .anySatisfy(r -> assertThat(r.kind()).isEqualTo(FlowStore.RouteKind.DEAD_LETTER));
        assertThat(store.nodeSamples(clusterId))
                .singleElement()
                .satisfies(n -> assertThat(n.errorKind()).as(n.error()).isNull());
    }

    @Test
    void aRowCapBelowTheClientCountIsReportedAsTruncation() throws Exception {
        String queue = "flow.it.cap." + System.nanoTime();
        var factory = new ActiveMQConnectionFactory(
                ArtemisIntegrationTest.coreUrl() + "?useTopologyForLoadBalancing=false",
                ArtemisIntegrationTest.BROKER_USER,
                ArtemisIntegrationTest.BROKER_PASSWORD);
        try (Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue(queue));
            session.createConsumer(session.createQueue(queue));

            var node = nodes.findById(nodeId).orElseThrow();
            ClientSampler.NodeResult result = sampler.sampleNode(clusterId, node, 1);

            assertThat(result.truncated()).isTrue();
            assertThat(result.sample().consumersSeen()).isEqualTo(1);
            assertThat(result.sample().consumersTotal()).isGreaterThanOrEqualTo(2);
        }
    }
}
