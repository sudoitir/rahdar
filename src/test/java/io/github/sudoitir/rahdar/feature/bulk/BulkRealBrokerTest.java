package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.kernel.security.GrantLoader;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A bulk run against a real Artemis (ADR-0093): pause, then delete, three queues in one run
 * each, end to end through {@link BulkService} and {@link BulkRunner} — no mocked single-queue
 * command. Proves the run finishes SUCCEEDED against a real broker and that every queue's audit
 * event names the run's event as its parent.
 */
class BulkRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    BulkService bulk;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository brokerNodes;

    @Autowired
    QueueSnapshotUpsert snapshots;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<String> queueNames = List.of("BULK." + run + ".1", "BULK." + run + ".2", "BULK." + run + ".3");
    private UUID clusterId;
    private ArtemisNode client;
    private String broker;

    @BeforeEach
    void setUp() {
        OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "bulk-real-" + run,
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
        UUID nodeId =
                brokerNodes.findByClusterIdOrderByNameAsc(clusterId).getFirst().getId();

        for (String name : queueNames) {
            queueOps.createQueue(client, broker, queue(name));
            snapshots.upsertBatch(
                    List.of(new QueueRow(clusterId, nodeId, name, name, "ANYCAST", true, 0, 0, 0, 0, 0, 0, 0, false)));
        }
    }

    private static Map<String, Object> queue(String name) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", name);
        config.put("address", name);
        config.put("routing-type", "ANYCAST");
        config.put("durable", true);
        config.put("auto-create-address", true);
        return config;
    }

    @AfterEach
    void cleanUp() {
        for (String name : queueNames) {
            quietly(() -> queueOps.destroyQueue(client, broker, name, true));
            quietly(() -> queueOps.deleteAddress(client, broker, name));
        }
        clusters.deleteById(clusterId);
        SecurityContextHolder.clearContext();
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException _) {
            // already gone
        }
    }

    private BulkRunDetailView runOperation(BulkOperation operation) {
        BulkRunDetailView preview = bulk.preview(clusterId, new BulkPreviewRequest(operation, queueNames, null, false));
        bulk.execute(
                clusterId,
                preview.run().id(),
                new BulkExecuteRequest(preview.run().planHash(), false, false));
        return awaitFinished(preview.run().id());
    }

    private BulkRunDetailView awaitFinished(UUID runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            BulkRunDetailView detail = bulk.get(clusterId, runId);
            if (detail.run().finishedAt() != null) {
                return detail;
            }
            LockSupport.parkNanos(20_000_000);
        }
        throw new AssertionError("The run did not finish");
    }

    private List<Long> childAuditIds(long parentId) {
        return jdbc.queryForList("SELECT id FROM audit_event WHERE parent_id = ?", Long.class, parentId);
    }

    @Test
    void pauseThenDeleteThreeQueuesSucceedsWithChildrenLinkedToTheRun() {
        BulkRunDetailView paused = runOperation(BulkOperation.PAUSE);
        assertThat(paused.run().status()).isEqualTo(BulkRunStatus.SUCCEEDED);
        assertThat(paused.items()).extracting(BulkItemView::status).containsOnly(BulkItemStatus.SUCCEEDED);
        assertThat(childAuditIds(paused.run().auditEventId())).hasSize(3);

        BulkRunDetailView deleted = runOperation(BulkOperation.DELETE);
        assertThat(deleted.run().status()).isEqualTo(BulkRunStatus.SUCCEEDED);
        assertThat(deleted.items()).extracting(BulkItemView::status).containsOnly(BulkItemStatus.SUCCEEDED);
        assertThat(childAuditIds(deleted.run().auditEventId())).hasSize(3);

        for (String name : queueNames) {
            assertThat(queueOps.boundQueues(client, MBeanNames.address(broker, name)))
                    .doesNotContain(name);
        }
    }
}
