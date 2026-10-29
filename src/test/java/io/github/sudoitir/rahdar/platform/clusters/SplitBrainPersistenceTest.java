package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The split-brain verdict is on the node rows, so every replica reads what the owner decided. */
class SplitBrainPersistenceTest extends PostgresIntegrationTest {

    private static final String PAIR = "f7734597-a768-11f1-aa4c-ceae3fa2df1d";

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NodeStateRecorder recorder;

    @Autowired
    ClusterDirectory directory;

    private UUID clusterId;

    @AfterEach
    void clean() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private void seed() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "sb-" + UUID.randomUUID(), null, null))
                .getId();
        nodes.save(BrokerNodeEntity.fromSeed(clusterId, "a-live", "PRIMARY", PAIR));
        nodes.save(BrokerNodeEntity.fromSeed(clusterId, "b-backup", "BACKUP", PAIR));
        nodes.save(BrokerNodeEntity.fromSeed(clusterId, "c-other", "NONE", "other-pair"));
        nodes.save(BrokerNodeEntity.fromSeed(clusterId, "d-unread", "NONE", null));
    }

    private Map<String, SplitBrainStatus> verdicts() {
        return directory.nodes(clusterId).stream()
                .collect(Collectors.toMap(ClusterNode::getName, ClusterNode::getSplitBrain));
    }

    @Test
    void aVerdictIsWrittenToEveryNodeOfItsNodeIdAndReadBackFromTheRows() {
        seed();

        recorder.recordSplitBrain(clusterId, Map.of(PAIR, SplitBrainStatus.CRITICAL));

        assertThat(verdicts())
                .containsEntry("a-live", SplitBrainStatus.CRITICAL)
                .containsEntry("b-backup", SplitBrainStatus.CRITICAL)
                .containsEntry("c-other", SplitBrainStatus.NONE)
                .containsEntry("d-unread", SplitBrainStatus.NONE);
        assertThat(SplitBrainStatus.byNodeId(directory.nodes(clusterId)))
                .isEqualTo(Map.of(PAIR, SplitBrainStatus.CRITICAL, "other-pair", SplitBrainStatus.NONE));
    }

    @Test
    void aLaterPassWithNoVerdictClearsIt() {
        seed();
        recorder.recordSplitBrain(clusterId, Map.of(PAIR, SplitBrainStatus.SUSPECTED));

        recorder.recordSplitBrain(clusterId, Map.of());

        assertThat(verdicts().values()).containsOnly(SplitBrainStatus.NONE);
    }

    @Test
    void theWorstVerdictOfAPairStandsForItsNodeId() {
        ClusterNode suspected = node("a", PAIR, SplitBrainStatus.SUSPECTED);
        ClusterNode critical = node("b", PAIR, SplitBrainStatus.CRITICAL);

        assertThat(SplitBrainStatus.byNodeId(List.of(suspected, critical)))
                .containsExactly(Map.entry(PAIR, SplitBrainStatus.CRITICAL));
        assertThat(SplitBrainStatus.byNodeId(List.of(critical, suspected)))
                .containsExactly(Map.entry(PAIR, SplitBrainStatus.CRITICAL));
    }

    private static ClusterNode node(String name, String nodeId, SplitBrainStatus status) {
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(UUID.randomUUID(), name, "PRIMARY", nodeId);
        node.recordSplitBrain(status);
        return node;
    }
}
