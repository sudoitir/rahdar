package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Disposition;
import io.github.sudoitir.openbroker.messaging.FlowControl;
import io.github.sudoitir.openbroker.messaging.LinkState;
import io.github.sudoitir.openbroker.messaging.OrderingScope;
import io.github.sudoitir.openbroker.messaging.Processor;
import io.github.sudoitir.openbroker.messaging.ProcessorOptions;
import io.github.sudoitir.openbroker.messaging.ReceiverOptions;
import io.github.sudoitir.openbroker.messaging.RetryPolicy;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.CuttableProxy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

/**
 * A broker link lost under a consumer is recovered by the SDK and recorded by Rahdar, naming the node,
 * and the message the consumer had not settled comes again (ADR-0211, core-transport spec).
 */
class BrokerLinksRecoveryTest extends ArtemisIntegrationTest {

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final BrokerLinks links = new BrokerLinks(new SimpleMeterRegistry());
    private CuttableProxy proxy;
    private ClusterConnection connection;
    private AutoCloseable watch;
    private String queue;

    @BeforeEach
    void setUp() throws Exception {
        queue = "links.recovery." + System.nanoTime();
        proxy = new CuttableProxy(ARTEMIS.getHost(), ARTEMIS.getMappedPort(61616));
        connection = cluster(nodeId, proxy.coreUrl());
        watch = connection.transport().orElseThrow().watch(links.listener(clusterId));
        var factory = new ActiveMQConnectionFactory(coreUrl(), BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createQueue(queue)).send(session.createTextMessage("order-1"));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        watch.close();
        connection.close();
        proxy.close();
    }

    @Test
    void aLostLinkIsRecordedRecoveringThenOpenOnItsNodeAndTheUnsettledMessageComesAgain() {
        List<String> handled = new CopyOnWriteArrayList<>();
        ProcessorOptions options = new ProcessorOptions(
                ReceiverOptions.consume().withFlow(FlowControl.none()),
                1,
                OrderingScope.GROUP,
                RetryPolicy.system(),
                Duration.ofSeconds(5));

        try (Processor processor = connection
                .transport()
                .orElseThrow()
                .process(
                        ResourceIds.of(connection, ResourceClass.SUBSCRIPTION, queue),
                        Scope.node(BrokerGateway.idOf(nodeId)),
                        options,
                        context -> {
                            handled.add(new String(
                                    context.message()
                                            .body()
                                            .bytes()
                                            .orElseThrow()
                                            .toByteArray(),
                                    StandardCharsets.UTF_8));
                            if (handled.size() == 1) {
                                // The link is lost before this delivery is settled.
                                proxy.cut();
                            }
                            return Disposition.accept();
                        })) {
            links.label(processor.id(), "plugin test/recovery");

            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> handled.size() >= 2);

            assertThat(handled).containsOnly("order-1");
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> !links.of(processor.id()).isEmpty()
                            && links.of(processor.id()).stream().allMatch(l -> l.state() == LinkState.OPEN));
            List<BrokerLinks.Link> lane = links.recent().stream()
                    .filter(l -> l.id().startsWith(processor.id()))
                    .toList();
            assertThat(lane).extracting(BrokerLinks.Link::state).contains(LinkState.RECOVERING, LinkState.OPEN);
            assertThat(lane)
                    .filteredOn(l -> l.state() == LinkState.RECOVERING)
                    .allSatisfy(l -> assertThat(l.cause()).isNotBlank());
            assertThat(lane).allSatisfy(l -> {
                assertThat(l.node()).isEqualTo(nodeId.toString());
                assertThat(l.owner()).isEqualTo("plugin test/recovery");
                assertThat(l.clusterId()).isEqualTo(clusterId);
            });
            Health health = links.health(true);
            assertThat(health.getDetails()).containsKeys("links", "notOpen", "recent");
        }
    }
}
