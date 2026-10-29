package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** An offset reset's audit row stays small at thousands of partitions. */
class KafkaOffsetResetAuditTest {

    @Test
    void aSmallResetKeepsEveryPartitionAndALargeOneKeepsATotalPerTopic() {
        Map<String, Long> small = new TreeMap<>();
        for (int p = 0; p < KafkaOffsetResets.SUMMARISE_ABOVE; p++) {
            small.put("orders/" + p, (long) p);
        }
        assertThat(KafkaOffsetResets.recorded(small)).isSameAs(small);

        Map<String, Long> large = new TreeMap<>();
        for (int p = 0; p < 3000; p++) {
            large.put("orders/" + p, 100L + p);
            large.put("billing/" + p, 5L);
        }
        Object row = KafkaOffsetResets.recorded(large);

        assertThat(row)
                .isEqualTo(Map.of(
                        "summarised",
                        true,
                        "topics",
                        Map.of(
                                "orders", Map.of("partitions", 3000L, "min", 100L, "max", 3099L),
                                "billing", Map.of("partitions", 3000L, "min", 5L, "max", 5L))));
    }
}
