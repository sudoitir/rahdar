package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Precision;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown.PartitionLag;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The partitions come slowest first, and a partition with no position says why instead of showing no lag. */
class KafkaBacklogBreakdownTest {

    private static Shard shard(int partition) {
        return Shard.partition(PartitionId.of(String.valueOf(partition)));
    }

    @Test
    void thePartitionsAreSlowestFirstWithTheOneWithoutAPositionLast() {
        Attributes attributes = Attributes.builder()
                .put(
                        MetricKeys.BACKLOG,
                        new MetricValue(
                                26, Precision.ESTIMATE, Optional.empty(), Map.of(shard(0), 6.0, shard(1), 20.0)))
                .putRaw("kafka.offsets", Map.of(0, 4L, 1, 9L))
                .putRaw("kafka.lag_unavailable", Map.of(2, "The group has no committed offset on this partition."))
                .build();

        List<PartitionLag> partitions = KafkaBacklogBreakdown.partitions(attributes, Map.of(0, 10L, 1, 29L, 2, 10L));

        assertThat(partitions)
                .extracting(p -> p.partition() + ":" + p.committed() + ":" + p.end() + ":" + p.lag())
                .containsExactly("1:9:29:20", "0:4:10:6", "2:null:10:null");
        assertThat(partitions.get(2).reason()).contains("no committed offset");
        assertThat(partitions.get(0).reason()).isNull();
    }

    @Test
    void aMissingEndIsTheCommittedOffsetPlusTheLagAndNeverZero() {
        Attributes attributes = Attributes.builder()
                .put(
                        MetricKeys.BACKLOG,
                        new MetricValue(6, Precision.ESTIMATE, Optional.empty(), Map.of(shard(0), 6.0)))
                .putRaw("kafka.offsets", Map.of(0, 4L))
                .build();

        assertThat(KafkaBacklogBreakdown.partitions(attributes, Map.of()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.end()).isEqualTo(10L);
                    assertThat(p.lag()).isEqualTo(6L);
                });
    }

    @Test
    void aPartitionWhoseEndCouldNotBeReadSaysSo() {
        Attributes attributes =
                Attributes.builder().putRaw("kafka.offsets", Map.of(0, 4L)).build();

        List<PartitionLag> partitions = KafkaBacklogBreakdown.partitions(attributes, Map.of());

        assertThat(partitions).singleElement().satisfies(p -> {
            assertThat(p.lag()).isNull();
            assertThat(p.end()).isNull();
            assertThat(p.reason()).contains("end offset");
        });
    }
}
