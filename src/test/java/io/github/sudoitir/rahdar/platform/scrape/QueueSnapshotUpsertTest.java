package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.internal.persistence.QueueSnapshotEntity;
import io.github.sudoitir.rahdar.platform.scrape.internal.persistence.QueueSnapshotRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link QueueSnapshotUpsert} against a real Postgres: the {@code ON CONFLICT}
 * upsert path and the per-node stale reap. Not {@code @Transactional} — the reap
 * needs {@code now()} to advance between batches, which only happens across
 * commits.
 */
class QueueSnapshotUpsertTest extends PostgresIntegrationTest {

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    QueueSnapshotRepository snapshots;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    private UUID clusterId;
    private UUID nodeId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId); // cascades to broker_node + subscription_snapshot
        }
    }

    private void givenClusterAndNode() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeId = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n-" + UUID.randomUUID(), "PRIMARY", null))
                .getId();
    }

    private QueueRow row(String queue, long messageCount) {
        return new QueueRow(
                clusterId,
                nodeId,
                "addr." + queue,
                queue,
                "ANYCAST",
                true,
                messageCount,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "artemis.queue",
                Map.of());
    }

    @Test
    void insertThenUpdateTheSamePkKeepsOneRowWithTheNewValues() {
        givenClusterAndNode();

        upsert.upsertBatch(List.of(row("Q1", 10), row("Q2", 5)));
        assertThat(snapshots.findByNodeId(nodeId)).hasSize(2);

        upsert.upsertBatch(List.of(row("Q1", 99)));

        QueueSnapshotEntity q1 =
                snapshots.findById(new QueueSnapshotEntity.Key(nodeId, "Q1")).orElseThrow();
        assertThat(q1.getMessageCount()).isEqualTo(99);
        assertThat(snapshots.findByNodeId(nodeId)).hasSize(2);
    }

    @Test
    void reapStaleDeletesOnlyRowsOlderThanTheSweepStartForThatNode() {
        givenClusterAndNode();

        upsert.upsertBatch(List.of(row("OLD", 1)));
        Instant afterOld = Instant.now();
        await().pollInterval(Duration.ofMillis(1)).until(() -> Instant.now().isAfter(afterOld));
        Instant sweepStart = Instant.now();
        await().pollInterval(Duration.ofMillis(1)).until(() -> Instant.now().isAfter(sweepStart));
        upsert.upsertBatch(List.of(row("FRESH", 2)));

        int reaped = upsert.reapStale(nodeId, sweepStart);

        assertThat(reaped).isEqualTo(1);
        assertThat(snapshots.findByNodeId(nodeId))
                .extracting(QueueSnapshotEntity::getQueueName)
                .containsExactly("FRESH");
    }
}
