package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The queue and address lifecycle against a real Artemis: create, create again, update, pause, resume, delete,
 * and an address's create and delete, each reporting what the broker did. The wire recording of this class is
 * what proves the requests the lifecycle sends did not change when it moved onto the broker SDK.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class QueueLifecycleRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String address = "QLIFE." + run + ".ADDR";
    private final String queue = "QLIFE." + run + ".Q";
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "queue-life-real-" + run,
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
    }

    @AfterEach
    void cleanUp() {
        quietly(() -> lifecycle.deleteQueue(clusterId, queue, false, true, true));
        quietly(() -> lifecycle.deleteAddress(clusterId, address, false));
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException _) {
            // already gone
        }
    }

    private static NodeStatus only(Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>) attempt).value();
        assertThat(outcome.nodes()).hasSize(1);
        assertThat(outcome.nodes().get(0).error()).as("the node's report").isNull();
        return outcome.nodes().get(0).status();
    }

    @Test
    void aQueueIsCreatedUpdatedPausedResumedAndDeleted() {
        assertThat(only(lifecycle.createAddress(clusterId, LifecycleFixtures.createAddress(address, "anycast"), false)))
                .isEqualTo(NodeStatus.APPLIED);
        CreateSubscriptionRequest request = LifecycleFixtures.createQueue(
                address, queue, "anycast", true, "color='red'", 5, null, null, null, null, null);

        assertThat(only(lifecycle.createQueue(clusterId, request, false))).isEqualTo(NodeStatus.APPLIED);
        assertThat(only(lifecycle.createQueue(clusterId, request, false))).isEqualTo(NodeStatus.ALREADY);

        assertThat(only(lifecycle.updateQueue(
                        clusterId,
                        queue,
                        LifecycleFixtures.updateQueue("color='blue'", 7, null, null, null, null),
                        false)))
                .isEqualTo(NodeStatus.APPLIED);

        assertThat(only(lifecycle.setPaused(clusterId, queue, true, false))).isEqualTo(NodeStatus.APPLIED);
        assertThat(only(lifecycle.setPaused(clusterId, queue, true, false))).isEqualTo(NodeStatus.ALREADY);
        assertThat(only(lifecycle.setPaused(clusterId, queue, false, false))).isEqualTo(NodeStatus.APPLIED);

        assertThat(only(lifecycle.deleteQueue(clusterId, queue, false, false, false)))
                .isEqualTo(NodeStatus.APPLIED);
        assertThat(only(lifecycle.deleteAddress(clusterId, address, false))).isEqualTo(NodeStatus.APPLIED);
    }

    @Test
    void aQueueThatExistsDifferentlyFailsNamingTheDifference() {
        CreateSubscriptionRequest request = LifecycleFixtures.createQueue(
                address, queue, "anycast", true, null, null, null, null, null, null, null);
        assertThat(only(lifecycle.createQueue(clusterId, request, false))).isEqualTo(NodeStatus.APPLIED);

        CreateSubscriptionRequest other = LifecycleFixtures.createQueue(
                address, queue, "anycast", true, "color='red'", null, null, null, null, null, null);
        LifecycleOutcome outcome =
                ((Attempt.Ok<LifecycleOutcome>) lifecycle.createQueue(clusterId, other, false)).value();

        assertThat(outcome.nodes().get(0).status()).isEqualTo(NodeStatus.FAILED);
        assertThat(outcome.nodes().get(0).error()).contains("filter");
    }
}
