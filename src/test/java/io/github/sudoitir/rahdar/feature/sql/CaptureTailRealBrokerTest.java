package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The console's live tail over a captured queue, against a real broker.
 *
 * <p>This is what a captured tail promises and a broker-reading one cannot give: a message
 * consumed the instant it arrives is still shown, because the capture divert took a copy of it
 * (ADR-0062 D2). Two things have to hold for that. The drain has to store its copy without
 * waiting for a full acknowledge batch, and the tail has to read the index the plan resolved to
 * rather than re-reading the queue the message is no longer on.
 *
 * <p>The scheduled capture pass is pushed out of reach so the tap installed here is the only one
 * in play, and the tick is driven from the test so it does not wait on the console's cadence.
 */
@ExtendWith(AdminAuthenticationExtension.class)
@TestPropertySource(
        properties = {
            "rahdar.capture.broker-role=amq",
            "rahdar.capture.reconcile-interval=1h",
            "rahdar.capture.flush-interval=200ms"
        })
class CaptureTailRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    SqlConsoleService console;

    @Autowired
    SqlTailPoller tailPoller;

    @Autowired
    MessageIndexService indexes;

    @Autowired
    CaptureReconciler reconciler;

    @Autowired
    QueueSnapshotUpsert snapshots;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterDirectory directory;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerConnections connections;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    RahdarInstance instance;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String address = "C8.TAIL." + run;

    private UUID clusterId;
    private UUID subscriptionId;
    private ArtemisNode client;
    private String broker;
    private ActiveMQConnectionFactory factory;
    private SqlTailPoller.Tail tail;

    @BeforeEach
    void setUp() {
        Attempt<ClusterDetail> attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "captured-tail-" + run,
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
        ClusterNode node = directory.nodes(clusterId).getFirst();
        clusterService.overrideNodeUrl(
                clusterId, node.getId(), new NodeOverrideRequest(null, ArtemisIntegrationTest.coreUrl()));
        client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
        factory = new ActiveMQConnectionFactory(
                ArtemisIntegrationTest.coreUrl(),
                ArtemisIntegrationTest.BROKER_USER,
                ArtemisIntegrationTest.BROKER_PASSWORD);

        queueOps.createQueue(
                client,
                broker,
                Map.of(
                        "name",
                        address,
                        "address",
                        address,
                        "routing-type",
                        "ANYCAST",
                        "durable",
                        true,
                        "auto-create-address",
                        true));
        // The scrape does not run in tests, and capture resolves its addresses from the snapshot.
        snapshots.upsertBatch(List.of(
                new QueueRow(clusterId, node.getId(), address, address, "ANYCAST", true, 0, 0, 0, 0, 0, 0, 0, false)));
    }

    @AfterEach
    void cleanUp() {
        if (tail != null) {
            tail.stop();
        }
        if (subscriptionId != null) {
            String divert = CaptureNames.of(instance.id(), address, subscriptionId);
            quietly(() -> indexes.delete(clusterId, subscriptionId));
            quietly(() -> divertOps.destroyDivert(client, broker, divert));
            quietly(() -> queueOps.destroyQueue(client, broker, CaptureNames.queueOf(divert), false));
        }
        quietly(() -> queueOps.destroyQueue(client, broker, address, true));
        quietly(() -> queueOps.deleteAddress(client, broker, address));
        quietly(factory::close);
        clusters.deleteById(clusterId);
    }

    @Test
    void aCapturedTailShowsAMessageConsumedTheInstantItArrived() throws Exception {
        subscriptionId = indexes.create(
                        clusterId,
                        new MessageIndexService.Spec(
                                address, null, 1, null, CaptureMode.CAPTURE, 1000L, null, null, null, null))
                .entity()
                .getId();
        reconciler.reconcileNow(clusterId);

        Collecting collecting = new Collecting();
        SqlConsoleService.Executed executed = console.run(clusterId, "SELECT * FROM \"" + address + "\"", collecting);
        assertThat(executed.plan().resolvedSource())
                .as("a captured queue plans onto the index")
                .isEqualTo(QueryAst.Source.INDEX);
        tail = tailPoller.start(clusterId, executed.plan(), collecting);
        executed.result().rows().forEach(tail::seen);

        // Taken off the queue before any read of it could see it: only the capture copy is left.
        send("consumed-at-once-" + run, true);

        await("the tail delivers the consumed message")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    tailPoller.tick();
                    return collecting.rows.stream().anyMatch(row -> ("consumed-at-once-" + run).equals(row.body()));
                });
    }

    private void send(String body, boolean consumeAtOnce) throws Exception {
        try (Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = consumeAtOnce ? session.createConsumer(session.createQueue(address)) : null;
            session.createProducer(session.createQueue(address)).send(session.createTextMessage(body));
            if (consumer != null) {
                assertThat(consumer.receive(10_000))
                        .as("the application consumed it")
                        .isNotNull();
            }
        }
    }

    /** Stands in for the open SSE stream: the static run's sink and the tail's listener. */
    private static final class Collecting implements BrokerQueryExecutor.Sink, SqlTailPoller.Listener {
        private final List<Row> rows = new CopyOnWriteArrayList<>();

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            // only the rows matter to this test
        }

        @Override
        public void status(SqlTailPoller.TailStatus status) {
            // only the rows matter to this test
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    private static void quietly(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception _) {
            // already gone, or never created
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
