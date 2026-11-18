package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.system.kafka.KafkaPlacement.Rack;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Replica order is the cluster's order. The preferred leader is the first replica, not whoever leads now. */
class KafkaPlacementTest {

    @Test
    void aLeaderThatIsTheSecondReplicaStaysSecondAndIsNotPreferred() {
        PartitionRow row = KafkaPlacement.row(partition(2, List.of(1, 2, 3), List.of(1, 2, 3)));

        assertThat(row.replicas()).containsExactly(1, 2, 3);
        assertThat(row.leader()).isEqualTo(2);
        assertThat(row.preferredLeader()).isFalse();
        assertThat(row.offlineReplicas()).isEmpty();
        assertThat(KafkaPlacement.needsAttention(row.leader(), row.replicas(), row.inSyncReplicas()))
                .isTrue();
    }

    @Test
    void theFirstReplicaLeadingIsPreferredAndAMissingLeaderMakesEveryReplicaOffline() {
        PartitionRow preferred = KafkaPlacement.row(partition(1, List.of(1, 2), List.of(1, 2)));
        assertThat(preferred.preferredLeader()).isTrue();
        assertThat(KafkaPlacement.needsAttention(preferred.leader(), preferred.replicas(), preferred.inSyncReplicas()))
                .isFalse();

        PartitionRow offline = KafkaPlacement.row(partition(null, List.of(1, 2), List.of(1)));
        assertThat(offline.leader()).isNull();
        assertThat(offline.replicas()).containsExactly(1, 2);
        assertThat(offline.offlineReplicas()).containsExactly(1, 2);
        assertThat(offline.preferredLeader()).isFalse();
    }

    @Test
    void aReplicaOutsideTheIsrIsOfflineAndTheOrderIsUnchanged() {
        PartitionRow row = KafkaPlacement.row(partition(1, List.of(4, 1, 2), List.of(1)));

        assertThat(row.replicas()).containsExactly(4, 1, 2);
        assertThat(row.offlineReplicas()).containsExactly(4, 2);
        assertThat(row.preferredLeader()).isFalse();
    }

    @Test
    void anAbsentTieredOffsetAndEligibleSetAreNotInvented() {
        Resource resource = partition(1, List.of(1), List.of(1));
        Attributes withReason = Attributes.builder()
                .putAll(resource.attributes())
                .putRaw(KafkaKinds.TIERED_OFFSET_REASON, "not tiered")
                .build();
        PartitionRow row = KafkaPlacement.row(
                new Resource(resource.id(), withReason, List.of(), resource.placements(), resource.observedAt()));

        assertThat(row.tieredOffset()).isNull();
        assertThat(row.tieredOffsetReason()).isEqualTo("not tiered");
        assertThat(row.eligibleLeaderReplicas()).isNull();
        assertThat(row.logDirsUnavailable()).isNull();
    }

    @Test
    void aSuggestionKeepsEveryCurrentReplicaAndAddsOneBrokerPerMissingRack() {
        List<Rack> brokers = List.of(
                new Rack(2, "a"),
                new Rack(1, "a"),
                new Rack(5, null),
                new Rack(4, "c"),
                new Rack(3, "b"),
                new Rack(6, "b"));

        assertThat(KafkaPlacement.suggest(List.of(2, 1), brokers)).containsExactly(2, 1, 3, 4);
        assertThat(KafkaPlacement.suggest(List.of(1, 3), brokers)).containsExactly(1, 3, 4);
        assertThat(KafkaPlacement.suggest(List.of(1, 3, 4), brokers)).containsExactly(1, 3, 4);
    }

    private static Resource partition(Integer leader, List<Integer> replicas, List<Integer> inSync) {
        Attributes.Builder attributes =
                Attributes.builder().putRaw(KafkaKinds.REPLICAS, replicas).putRaw(KafkaKinds.IN_SYNC_REPLICAS, inSync);
        if (leader != null) {
            attributes.putRaw(KafkaKinds.LEADER, leader.longValue());
        }
        return new Resource(
                ResourceId.of("kafka.partition", "orders", "0"),
                attributes.build(),
                List.of(),
                List.of(),
                Instant.EPOCH);
    }
}
