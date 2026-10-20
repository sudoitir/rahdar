package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Turning capture off takes its divert and capture queue off a real broker before the request
 * returns. The drain stops at once, so a divert left for the next scheduled pass copies every
 * message into a queue nothing empties.
 *
 * <p>The scheduled pass is pushed out of reach so only the disable can be what removed them.
 */
@ExtendWith(AdminAuthenticationExtension.class)
@TestPropertySource(properties = {"rahdar.capture.broker-role=amq", "rahdar.capture.reconcile-interval=1h"})
class CaptureDisableRealBrokerTest extends PostgresIntegrationTest {

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
    private final String address = "C4.CAPTURE." + run;

    private UUID clusterId;
    private UUID subscriptionId;
    private ArtemisNode client;
    private String broker;

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "capture-disable-" + run,
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

        queueOps.createAddress(client, broker, address, "ANYCAST");
        // The scrape is not scheduled in tests; capture resolves its addresses from the snapshot.
        snapshots.upsertBatch(List.of(new QueueRow(
                clusterId,
                node.getId(),
                address,
                address,
                "ANYCAST",
                true,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "artemis.queue")));
    }

    @AfterEach
    void cleanUp() {
        String divert = CaptureNames.of(instance.id(), address, subscriptionId);
        quietly(() -> indexes.delete(clusterId, subscriptionId));
        quietly(() -> divertOps.destroyDivert(client, broker, divert));
        quietly(() -> queueOps.destroyQueue(client, broker, CaptureNames.queueOf(divert), false));
        quietly(() -> queueOps.deleteAddress(client, broker, address));
        clusters.deleteById(clusterId);
    }

    @Test
    void disablingCaptureRemovesItsDivertAndCaptureQueueBeforeTheRequestReturns() {
        subscriptionId = indexes.create(
                        clusterId,
                        new MessageIndexService.Spec(
                                address, null, 1, null, CaptureMode.CAPTURE, 1000L, null, null, null, null))
                .entity()
                .getId();
        reconciler.reconcileNow(clusterId);
        String divert = CaptureNames.of(instance.id(), address, subscriptionId);
        assertThat(divertOps.find(client, divert)).isPresent();
        assertThat(queueNames()).contains(CaptureNames.queueOf(divert));

        indexes.update(
                clusterId,
                subscriptionId,
                new MessageIndexService.Spec(null, null, null, false, null, null, null, null, null, null));

        assertThat(divertOps.find(client, divert)).isEmpty();
        assertThat(queueNames()).doesNotContain(CaptureNames.queueOf(divert));
    }

    private Set<String> queueNames() {
        JsonNode value =
                client.single(ManagementRequest.read(broker, "QueueNames")).attribute("QueueNames");
        return Set.copyOf(value.valueStream().map(JsonNode::asString).toList());
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException _) {
            // already gone, or never created
        }
    }
}
